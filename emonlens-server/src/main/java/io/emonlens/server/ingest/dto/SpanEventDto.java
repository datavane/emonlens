package io.emonlens.server.ingest.dto;

import java.util.Map;

import lombok.Data;

/**
 * 通用 HTTP/JSON ingest 的单条 span 事件（OTLP 端点后续版本提供）。
 * 时间为 ISO-8601 字符串；带时区偏移时统一归一化为 UTC 存储。
 * 校验由 IngestService 逐条执行并返回明细，不使用 Bean Validation。
 */
@Data
public class SpanEventDto {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";

    private String traceId;

    private String spanId;

    private String parentSpanId;

    private String agentKey;

    private String agentVersion;

    private String spanType;

    private String spanName;

    private String startTime;

    private String endTime;

    private String status;

    private String errorType;

    private String modelName;

    private String toolName;

    private Long inputTokens;

    private Long outputTokens;

    /** 输入 tokens 中命中提示缓存的量（计费/缓存命中率分析用；inputTokens 为含缓存的总输入） */
    private Long cachedInputTokens;

    private Map<String, Object> attributes;

    /** 内容采集（prompt/completion/tool_payload -> 正文），是否落库由 content_capture_mode 决定 */
    private Map<String, String> content;

    /** LLM prompt / tool input（REDACTED 模式自动脱敏） */
    private String input;

    /** LLM completion / tool output（REDACTED 模式自动脱敏） */
    private String output;

    /** 多轮对话分组 ID */
    private String sessionId;
    private String environment;

    /** 终端用户 ID（用户洞察按此聚合；SDK @observe/set_session 透传） */
    private String userId;
}
