package io.emonlens.server.api;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.server.api.dto.FindingVO;
import io.emonlens.server.api.dto.PageVO;
import io.emonlens.server.api.dto.TraceDtos;
import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.storage.entity.FindingEntity;
import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.FindingMapper;
import io.emonlens.server.storage.query.TraceQueryService;
import io.emonlens.server.storage.trace.TraceAssemblyService;

import lombok.RequiredArgsConstructor;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.WorkspaceGuard;

@RestController
@RequestMapping("/api/v1/traces")
@RequiresPermission(Permissions.TRACE_QUERY)
@RequiredArgsConstructor
public class TraceController {

    private final TraceQueryService traceQueryService;
    private final TraceAssemblyService traceAssemblyService;
    private final FindingMapper findingMapper;

    @PostMapping("/search")
    public ApiResult<PageVO<TraceDtos.SummaryVO>> search(@RequestBody TraceDtos.SearchRequest request) {
        Page<TraceSummaryEntity> page = traceQueryService.searchSummaries(
                CurrentContext.require().getWorkspaceId(), request.getAgentKeys(), request.getStatuses(),
                parseTime(request.getTimeFrom()), parseTime(request.getTimeTo()),
                request.getMinDurationMs(), request.getMaxDurationMs(), request.getUserId(),
                request.getSessionId(), request.getHasError(), request.getMinCostUsd(), request.getMaxCostUsd(),
                request.getTags(), request.getEnvironments(), request.getModelNames(),
                request.getPromptName(), request.getPromptVersion(), request.getQuery(),
                request.getSortBy(), request.getSortOrder(), request.getPageNumber(), request.getPageSize());
        return ApiResult.ok(PageVO.from(page, page.getRecords().stream()
                .map(this::toSummaryVO).collect(Collectors.toList())));
    }

    @GetMapping("/{traceId}")
    public ApiResult<TraceDtos.DetailVO> detail(@PathVariable String traceId) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        TraceSummaryEntity summary = traceAssemblyService.loadSummary(workspaceId, traceId);
        if (summary == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "trace not found: " + traceId);
        }
        WorkspaceGuard.owned(summary.getWorkspaceId());
        List<SpanEventEntity> spans = traceAssemblyService.loadSpans(workspaceId, traceId);
        // 原始输入/输出属于内容权限：仅有 trace:query 的角色只能看元数据
        boolean contentAllowed = CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT);
        List<FindingEntity> findings = findingMapper.selectList(
                Wrappers.<FindingEntity>lambdaQuery()
                        .eq(FindingEntity::getWorkspaceId, workspaceId)
                        .eq(FindingEntity::getTraceId, traceId)
                        .orderByDesc(FindingEntity::getCreatedAt));
        TraceDtos.DetailVO detail = new TraceDtos.DetailVO();
        detail.setSummary(toSummaryVO(summary));
        detail.setSpans(spans.stream().map(s -> toSpanVO(s, contentAllowed)).collect(Collectors.toList()));
        detail.setFindings(findings.stream().map(this::toFindingVO).collect(Collectors.toList()));
        return ApiResult.ok(detail);
    }

    private TraceDtos.SummaryVO toSummaryVO(TraceSummaryEntity entity) {
        TraceDtos.SummaryVO vo = new TraceDtos.SummaryVO();
        vo.setTraceId(entity.getTraceId());
        vo.setAgentKey(entity.getAgentKey());
        vo.setAgentVersion(entity.getAgentVersion());
        vo.setStatus(entity.getStatus());
        vo.setSpanCount(entity.getSpanCount());
        vo.setErrorCount(entity.getErrorCount());
        vo.setStartTime(entity.getStartTime());
        vo.setEndTime(entity.getEndTime());
        vo.setDurationMs(entity.getDurationMs());
        vo.setUserId(entity.getUserId());
        vo.setSessionId(entity.getSessionId());
        vo.setEnvironment(entity.getEnvironment());
        vo.setModelName(entity.getModelName());
        vo.setPromptName(entity.getPromptName());
        vo.setPromptVersion(entity.getPromptVersion());
        vo.setInputPreview(entity.getInputPreview());
        vo.setOutputPreview(entity.getOutputPreview());
        vo.setTotalCostUsd(entity.getTotalCostUsd());
        return vo;
    }

    private TraceDtos.SpanVO toSpanVO(SpanEventEntity entity, boolean contentAllowed) {
        TraceDtos.SpanVO vo = new TraceDtos.SpanVO();
        vo.setSpanId(entity.getSpanId());
        vo.setParentSpanId(entity.getParentSpanId());
        vo.setSpanType(entity.getSpanType());
        vo.setSpanName(entity.getSpanName());
        vo.setModelName(entity.getModelName());
        vo.setToolName(entity.getToolName());
        vo.setStartTime(entity.getStartTime());
        vo.setEndTime(entity.getEndTime());
        vo.setDurationMs(entity.getDurationMs());
        vo.setStatus(entity.getStatus());
        vo.setErrorType(entity.getErrorType());
        vo.setInputTokens(entity.getInputTokens());
        vo.setOutputTokens(entity.getOutputTokens());
        vo.setCachedInputTokens(entity.getCachedInputTokens());
        vo.setInputText(contentAllowed ? entity.getInputText() : null);
        vo.setOutputText(contentAllowed ? entity.getOutputText() : null);
        vo.setCostUsd(entity.getCostUsd());
        vo.setLevel(entity.getLevel());
        vo.setAttributes(entity.getAttributes());
        return vo;
    }

    private FindingVO toFindingVO(FindingEntity entity) {
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

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "time must be ISO-8601 local datetime: " + value);
        }
    }
}
