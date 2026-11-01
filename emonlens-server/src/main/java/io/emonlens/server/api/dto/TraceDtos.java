package io.emonlens.server.api.dto;

import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

public final class TraceDtos {

    private TraceDtos() {
    }

    @Data
    public static class SearchRequest {
        private List<String> agentKeys;
        private List<String> statuses;
        /** ISO-8601，如 2026-08-30T00:00:00 */
        private String timeFrom;
        private String timeTo;
        private Long minDurationMs;
        private String userId;
        /** 命中任一标签即可 */
        private java.util.List<String> tags;
        private java.util.List<String> environments;
        /** 命中任一模型即可（Langfuse 风格 model 过滤） */
        private java.util.List<String> modelNames;
        /** Prompt 关联过滤（Langfuse prompt linkage） */
        private String promptName;
        private String promptVersion;
        /** 内容模糊搜索（输入/输出摘要、traceId） */
        private String query;
        /** 会话过滤（Langfuse session 维度） */
        private String sessionId;
        private Long maxDurationMs;
        private java.math.BigDecimal minCostUsd;
        private java.math.BigDecimal maxCostUsd;
        /** 仅看有错误的 trace（errorCount > 0，含部分 span 错误） */
        private Boolean hasError;
        /** startTime | durationMs | totalCostUsd | errorCount */
        private String sortBy;
        /** asc | desc（默认 desc） */
        private String sortOrder;
        private int pageNumber = 1;
        private int pageSize = 20;
    }

    @Data
    public static class SummaryVO {
        private String traceId;
        private String agentKey;
        private String agentVersion;
        private String status;
        private Integer spanCount;
        private Integer errorCount;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Long durationMs;
        /** Langfuse 风格列表直读字段 */
        private String userId;
        private String sessionId;
        private String environment;
        private String modelName;
        private String promptName;
        private String promptVersion;
        private String inputPreview;
        private String outputPreview;
        private java.math.BigDecimal totalCostUsd;
    }

    @Data
    public static class DetailVO {
        private SummaryVO summary;
        private List<SpanVO> spans;
        private List<FindingVO> findings;
    }

    @Data
    public static class SpanVO {
        private String spanId;
        private String parentSpanId;
        private String spanType;
        private String spanName;
        private String modelName;
        private String toolName;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Long durationMs;
        private String status;
        private String errorType;
        private Long inputTokens;
        private Long outputTokens;
        private Long cachedInputTokens;
        /** span 级 LLM I/O 原文（受内容采集模式控制） */
        private String inputText;
        private String outputText;
        private java.math.BigDecimal costUsd;
        private String level;
        /** SDK attributes 原文 JSON（prompt 关联等元数据） */
        private String attributes;
    }
}
