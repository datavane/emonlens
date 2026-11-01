package io.emonlens.server.api;

import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.server.api.dto.IncidentVO;
import io.emonlens.server.api.dto.PageVO;
import io.emonlens.common.ApiResult;
import io.emonlens.server.engine.incident.IncidentService;
import io.emonlens.server.storage.entity.IncidentEntity;

import lombok.RequiredArgsConstructor;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;

@RestController
@RequestMapping("/api/v1/incidents")
@RequiredArgsConstructor
public class IncidentController {

    private final IncidentService incidentService;

    @GetMapping
    @RequiresPermission(Permissions.INCIDENT_QUERY)
    public ApiResult<PageVO<IncidentVO>> page(@RequestParam(required = false) String agentKey,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "1") int pageNumber,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        Page<IncidentEntity> page = incidentService.page(agentKey, status, pageNumber, pageSize);
        return ApiResult.ok(PageVO.from(page, page.getRecords().stream()
                .map(this::toVO).collect(Collectors.toList())));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.INCIDENT_QUERY)
    public ApiResult<IncidentVO> get(@PathVariable String id) {
        return ApiResult.ok(toVO(incidentService.get(id)));
    }

    @PostMapping("/{id}/ack")
    @RequiresPermission(Permissions.INCIDENT_MANAGE)
    @Audit(action = "incident.ack", resourceType = "incident")
    public ApiResult<IncidentVO> ack(@PathVariable String id) {
        return ApiResult.ok(toVO(incidentService.ack(id)));
    }

    @PostMapping("/{id}/resolve")
    @RequiresPermission(Permissions.INCIDENT_MANAGE)
    @Audit(action = "incident.resolve", resourceType = "incident")
    public ApiResult<IncidentVO> resolve(@PathVariable String id) {
        return ApiResult.ok(toVO(incidentService.resolve(id)));
    }

    private IncidentVO toVO(IncidentEntity entity) {
        IncidentVO vo = new IncidentVO();
        vo.setId(entity.getId());
        vo.setAgentKey(entity.getAgentKey());
        vo.setTitle(entity.getTitle());
        vo.setSeverity(entity.getSeverity());
        vo.setStatus(entity.getStatus());
        vo.setFindingCount(entity.getFindingCount());
        vo.setFirstSeenAt(entity.getFirstSeenAt());
        vo.setLastSeenAt(entity.getLastSeenAt());
        return vo;
    }
}
