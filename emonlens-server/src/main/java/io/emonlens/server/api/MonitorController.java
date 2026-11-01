package io.emonlens.server.api;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

import io.emonlens.server.api.dto.MonitorDtos;
import io.emonlens.server.api.dto.PageVO;
import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.engine.monitor.MonitorService;
import io.emonlens.server.storage.entity.MonitorEntity;

import lombok.RequiredArgsConstructor;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;

@RestController
@RequestMapping("/api/v1/monitors")
@RequiredArgsConstructor
public class MonitorController {

    private final MonitorService monitorService;
    private final io.emonlens.server.storage.mapper.MonitorStateMapper monitorStateMapper;
    private final ObjectMapper objectMapper;

    @PostMapping
    @RequiresPermission(Permissions.MONITOR_MANAGE)
    @Audit(action = "monitor.create", resourceType = "monitor")
    public ApiResult<MonitorDtos.VO> create(@Valid @RequestBody MonitorDtos.CreateRequest request) {
        MonitorEntity entity = monitorService.create(CurrentContext.require().getWorkspaceId(),
                request.getAgentKey(), request.getMonitorKey(), request.getKind(), request.getSpec(),
                request.getSeverity(), request.getDimension());
        return ApiResult.ok(toVO(entity));
    }

    @GetMapping
    @RequiresPermission(Permissions.MONITOR_QUERY)
    public ApiResult<PageVO<MonitorDtos.VO>> page(@RequestParam(required = false) String agentKey,
                                                  @RequestParam(defaultValue = "1") int pageNumber,
                                                  @RequestParam(defaultValue = "20") int pageSize) {
        Page<MonitorEntity> page = monitorService.page(CurrentContext.require().getWorkspaceId(),
                agentKey, pageNumber, pageSize);
        return ApiResult.ok(PageVO.from(page, page.getRecords().stream()
                .map(this::toVO).collect(Collectors.toList())));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.MONITOR_QUERY)
    public ApiResult<MonitorDtos.VO> get(@PathVariable String id) {
        return ApiResult.ok(toVO(monitorService.getById(id)));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permissions.MONITOR_MANAGE)
    @Audit(action = "monitor.update", resourceType = "monitor")
    public ApiResult<MonitorDtos.VO> update(@PathVariable String id,
                                            @RequestBody MonitorDtos.UpdateRequest request) {
        String specJson = serializeSpec(request.getSpec());
        return ApiResult.ok(toVO(monitorService.update(id, specJson, request.getSeverity(),
                request.getDimension())));
    }

    @PostMapping("/{id}/validate")
    @RequiresPermission(Permissions.MONITOR_QUERY)
    public ApiResult<List<String>> validate(@PathVariable String id) {
        MonitorEntity entity = monitorService.getById(id);
        return ApiResult.ok(monitorService.validateSpec(entity.getKind(), parseSpec(entity.getSpecJson())));
    }

    @PostMapping("/{id}/enable")
    @RequiresPermission(Permissions.MONITOR_MANAGE)
    @Audit(action = "monitor.enable", resourceType = "monitor")
    public ApiResult<MonitorDtos.VO> enable(@PathVariable String id) {
        return ApiResult.ok(toVO(monitorService.setEnabled(id, true)));
    }

    @PostMapping("/{id}/disable")
    @RequiresPermission(Permissions.MONITOR_MANAGE)
    @Audit(action = "monitor.disable", resourceType = "monitor")
    public ApiResult<MonitorDtos.VO> disable(@PathVariable String id) {
        return ApiResult.ok(toVO(monitorService.setEnabled(id, false)));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permissions.MONITOR_MANAGE)
    @Audit(action = "monitor.delete", resourceType = "monitor")
    public ApiResult<Map<String, Object>> delete(@PathVariable String id) {
        MonitorEntity entity = monitorService.getById(id);
        io.emonlens.server.auth.WorkspaceGuard.owned(entity.getWorkspaceId());
        monitorService.delete(id);
        // 触达状态（连续命中计数/冷却）随之清除；历史 Incident/Finding 保留作为审计轨迹
        monitorStateMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<io.emonlens.server.storage.entity.MonitorStateEntity>lambdaQuery()
                .eq(io.emonlens.server.storage.entity.MonitorStateEntity::getMonitorId, id));
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("monitorKey", entity.getMonitorKey());
        return ApiResult.ok(m);
    }

    @PostMapping("/{id}/dry-run")
    @RequiresPermission(Permissions.MONITOR_QUERY)
    public ApiResult<List<MonitorDtos.DryRunItemVO>> dryRun(@PathVariable String id,
                                                            @RequestBody(required = false) MonitorDtos.DryRunRequest request) {
        int limit = request == null || request.getLimit() <= 0 ? 20 : request.getLimit();
        return ApiResult.ok(monitorService.dryRun(id, limit).stream().map(item -> {
            MonitorDtos.DryRunItemVO vo = new MonitorDtos.DryRunItemVO();
            vo.setTraceId(item.getTraceId());
            vo.setMatched(item.isMatched());
            vo.setViolations(item.getViolations());
            return vo;
        }).collect(Collectors.toList()));
    }

    private MonitorDtos.VO toVO(MonitorEntity entity) {
        MonitorDtos.VO vo = new MonitorDtos.VO();
        vo.setId(entity.getId());
        vo.setWorkspaceId(entity.getWorkspaceId());
        vo.setAgentKey(entity.getAgentKey());
        vo.setMonitorKey(entity.getMonitorKey());
        vo.setKind(entity.getKind());
        vo.setSpec(parseSpec(entity.getSpecJson()));
        vo.setSeverity(entity.getSeverity());
        vo.setDimension(entity.getDimension());
        vo.setEnabled(entity.getEnabled());
        vo.setVersion(entity.getVersion());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private Map<String, Object> parseSpec(String specJson) {
        try {
            return objectMapper.readValue(specJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "spec_json is not valid JSON object");
        }
    }

    private String serializeSpec(Map<String, Object> spec) {
        if (spec == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "spec is not valid JSON object");
        }
    }
}
