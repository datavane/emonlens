package io.emonlens.server.api;

import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.server.api.dto.FindingVO;
import io.emonlens.server.api.dto.PageVO;
import io.emonlens.common.ApiResult;
import io.emonlens.server.storage.entity.FindingEntity;
import io.emonlens.server.storage.mapper.FindingMapper;

import lombok.RequiredArgsConstructor;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.auth.CurrentContext;

@RestController
@RequestMapping("/api/v1/findings")
@RequiresPermission(Permissions.FINDING_QUERY)
@RequiredArgsConstructor
public class FindingController {

    private final FindingMapper findingMapper;

    @GetMapping
    public ApiResult<PageVO<FindingVO>> page(@RequestParam(required = false) String agentKey,
                                             @RequestParam(required = false) String severity,
                                             @RequestParam(required = false) String traceId,
                                             @RequestParam(defaultValue = "1") int pageNumber,
                                             @RequestParam(defaultValue = "20") int pageSize) {
        LambdaQueryWrapper<FindingEntity> wrapper = Wrappers.lambdaQuery(FindingEntity.class)
                .eq(FindingEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId());
        if (agentKey != null && !agentKey.isEmpty()) {
            wrapper.eq(FindingEntity::getAgentKey, agentKey);
        }
        if (severity != null && !severity.isEmpty()) {
            wrapper.eq(FindingEntity::getSeverity, severity);
        }
        if (traceId != null && !traceId.isEmpty()) {
            wrapper.eq(FindingEntity::getTraceId, traceId);
        }
        wrapper.orderByDesc(FindingEntity::getCreatedAt);
        Page<FindingEntity> page = findingMapper.selectPage(PageVO.safePage(pageNumber, pageSize), wrapper);
        return ApiResult.ok(PageVO.from(page, page.getRecords().stream()
                .map(this::toVO).collect(Collectors.toList())));
    }

    private FindingVO toVO(FindingEntity entity) {
        FindingVO vo = new FindingVO();
        vo.setId(entity.getId());
        vo.setAgentKey(entity.getAgentKey());
        vo.setAgentVersion(entity.getAgentVersion());
        vo.setTraceId(entity.getTraceId());
        vo.setSpanId(entity.getSpanId());
        vo.setDimension(entity.getDimension());
        vo.setType(entity.getType());
        vo.setSeverity(entity.getSeverity());
        vo.setDetectorId(entity.getDetectorId());
        vo.setMessage(entity.getMessage());
        vo.setEvidence(entity.getEvidence());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
