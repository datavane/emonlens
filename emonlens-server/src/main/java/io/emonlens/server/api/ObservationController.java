package io.emonlens.server.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.common.ApiResult;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.api.dto.PageVO;
import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.span.SpanEventStore;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * Observation 查询：span 平铺分页视图（对标 Langfuse Observations 列表）。
 */
@RestController
@RequestMapping("/api/v1/observations")
@RequiredArgsConstructor
public class ObservationController {

    private final SpanEventStore spanStore;
    private final io.emonlens.server.ingest.IngestService ingestService;

    @GetMapping
    @RequiresPermission(Permissions.TRACE_QUERY)
    public ApiResult<PageVO<ObservationVO>> page(
            @RequestParam(required = false) String agentKey,
            @RequestParam(required = false) String spanType,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String traceId,
            /** span 名称/工具名关键词 */
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Long minDurationMs,
            /** ISO-8601；Observations 与 Traces 视图共用时间范围 */
            @RequestParam(required = false) String timeFrom,
            @RequestParam(required = false) String timeTo,
            @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "20") int pageSize) {
        SpanEventStore.SpanQuery query0 = SpanEventStore.SpanQuery.builder()
                .workspaceId(CurrentContext.require().getWorkspaceId())
                .agentKey(agentKey).spanType(spanType).modelName(model).status(status).traceId(traceId)
                .spanNameLike(query).minDurationMs(minDurationMs)
                .startTimeFrom(parseTime(timeFrom)).startTimeTo(parseTime(timeTo))
                .orderDesc(true)
                .pageNumber(Math.max(pageNumber, 1)).pageSize(Math.max(1, Math.min(pageSize, 1000)))
                .build();
        SpanEventStore.SpanPage<SpanEventEntity> result = spanStore.search(query0);
        return ApiResult.ok(PageVO.of(result.getRecords().stream()
                .map(this::toVO).collect(Collectors.toList()), result.getTotal(), Math.max(pageNumber, 1),
                Math.max(1, Math.min(pageSize, 1000))));
    }

    /** 容量监控（运维轮询）：队列水位与累计接收/丢弃计数 */
    @GetMapping("/stats")
    @RequiresPermission(Permissions.TRACE_QUERY)
    public ApiResult<Map<String, Number>> stats() {
        return ApiResult.ok(ingestService.stats());
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.length() > 19 ? value.substring(0, 19) : value);
        } catch (Exception e) {
            return null;
        }
    }

    private ObservationVO toVO(SpanEventEntity e) {
        ObservationVO vo = new ObservationVO();
        vo.setTraceId(e.getTraceId());
        vo.setSpanId(e.getSpanId());
        vo.setParentSpanId(e.getParentSpanId());
        vo.setAgentKey(e.getAgentKey());
        vo.setSpanType(e.getSpanType());
        vo.setSpanName(e.getSpanName());
        vo.setModelName(e.getModelName());
        vo.setToolName(e.getToolName());
        vo.setStatus(e.getStatus());
        vo.setErrorType(e.getErrorType());
        vo.setDurationMs(e.getDurationMs());
        vo.setInputTokens(e.getInputTokens());
        vo.setOutputTokens(e.getOutputTokens());
        vo.setCachedInputTokens(e.getCachedInputTokens());
        vo.setCostUsd(e.getCostUsd());
        vo.setStartTime(e.getStartTime());
        return vo;
    }

    @Data
    public static class ObservationVO {
        private String traceId;
        private String spanId;
        private String parentSpanId;
        private String agentKey;
        private String spanType;
        private String spanName;
        private String modelName;
        private String toolName;
        private String status;
        private String errorType;
        private Long durationMs;
        private Long inputTokens;
        private Long outputTokens;
        /** 命中缓存的输入 tokens（prompt cache hit；成本核算/列表展示） */
        private Long cachedInputTokens;
        private java.math.BigDecimal costUsd;
        private java.time.LocalDateTime startTime;
    }
}
