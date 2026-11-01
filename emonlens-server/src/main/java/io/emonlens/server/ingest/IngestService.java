package io.emonlens.server.ingest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.ingest.dto.IngestResponse;
import io.emonlens.server.ingest.dto.SpanEventDto;
import io.emonlens.server.ingest.RedactionService;
import io.emonlens.server.storage.entity.SpanEventEntity;

import lombok.extern.slf4j.Slf4j;

/**
 * ingest 校验 + 有界队列。
 * 队列满时按低风险丢弃策略丢弃并计数（设计文档 §4.2），不阻塞业务上报方。
 * drainBatch 供 worker 的微批写线程消费。
 */
@Slf4j
@Service
public class IngestService {

    private static final Set<String> ALLOWED_SPAN_TYPES = Set.of(
            "agent", "workflow", "llm", "tool", "retrieval", "guardrail", "handoff", "memory", "approval",
            "business_action");

    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;
    private final RedactionService redactionService;
    private final IngestRateLimiter rateLimiter;
    private final BlockingQueue<SpanEnvelope> queue;
    private final AtomicLong droppedTotal = new AtomicLong();
    private final AtomicLong acceptedTotal = new AtomicLong();

    public IngestService(ObjectMapper objectMapper, RedactionService redactionService,
                         EmonLensProperties properties, IngestRateLimiter rateLimiter) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.redactionService = redactionService;
        this.rateLimiter = rateLimiter;
        this.queue = new ArrayBlockingQueue<>(properties.getIngest().getQueueCapacity());
    }

    /** 单批上报上限（生产保护：防止误用打爆内存队列） */
    public static final int MAX_BATCH_SIZE = 500;

    /** 落库列宽保护：必填标识超限直接拒绝；描述字段超限截断；attributes 超限整条拒绝。 */
    private static final int MAX_TRACE_ID = 64;
    private static final int MAX_SPAN_ID = 32;
    private static final int MAX_AGENT_KEY = 128;
    private static final int MAX_AGENT_VERSION = 64;
    private static final int MAX_SPAN_NAME = 256;
    private static final int MAX_ERROR_TYPE = 128;
    private static final int MAX_MODEL_NAME = 128;
    private static final int MAX_TOOL_NAME = 128;
    private static final int MAX_SESSION_ID = 64;
    private static final int MAX_USER_ID = 64;
    private static final int MAX_ENVIRONMENT = 32;
    /** et_span_event.attributes 为 TEXT(65535 字节)，按字符数保守留出多字节余量 */
    private static final int MAX_ATTRIBUTES_CHARS = 60_000;

    public IngestResponse accept(TrustContext context, List<SpanEventDto> spans) {
        if (spans.size() > MAX_BATCH_SIZE) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "batch too large: " + spans.size() + " spans, max " + MAX_BATCH_SIZE + " per request");
        }
        // 每 workspace 令牌桶限流：单租户刷量不挤占其他 workspace 的队列容量（整批拒绝，客户端退避重试）
        if (!rateLimiter.tryConsume(context.getWorkspaceId(), spans.size())) {
            log.warn("ingest rate limited: workspace={} spans={} limitPerMinute={} limitedTotal={}",
                    context.getWorkspaceId(), spans.size(),
                    properties.getIngest().getRateLimitSpansPerMinute(), rateLimiter.getLimitedTotal());
            throw new EmonLensException(ErrorCode.RATE_LIMITED,
                    "ingest rate limited for workspace: reduce span rate or raise "
                            + "emonlens.ingest.rate-limit-spans-per-minute");
        }
        List<IngestResponse.RejectedItem> rejected = new ArrayList<>();
        List<SpanEnvelope> accepted = new ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            SpanEventDto dto = spans.get(i);
            String reason = validate(dto);
            SpanEventEntity entity = null;
            if (reason == null) {
                entity = buildEntity(context, dto);
                reason = validateEntity(entity);
            }
            if (reason != null) {
                rejected.add(new IngestResponse.RejectedItem(i, reason));
            } else {
                accepted.add(new SpanEnvelope(entity, dto.getContent()));
            }
        }
        if (!rejected.isEmpty()) {
            // 拒收可观测：落日志（采样前 3 条原因），客户端响应里也有明细
            String reasons = rejected.stream().limit(3)
                    .map(r -> "#" + r.getIndex() + " " + r.getReason())
                    .collect(Collectors.joining("; "));
            log.warn("ingest rejected {}/{} spans: {}{}", rejected.size(), spans.size(), reasons,
                    rejected.size() > 3 ? " …" : "");
        }
        int dropped = 0;
        for (SpanEnvelope envelope : accepted) {
            if (!queue.offer(envelope)) {
                dropped++;
                droppedTotal.incrementAndGet();
            } else {
                acceptedTotal.incrementAndGet();
            }
        }
        if (dropped > 0) {
            log.warn("ingest queue full, dropped {} spans, droppedTotal={}", dropped, droppedTotal.get());
        }
        return new IngestResponse(accepted.size() - dropped, rejected, dropped, droppedTotal.get());
    }

    /**
     * 分块接收：OTel Collector 默认批 8192 span，超过单批上限直接 400 会让采集端反复失败丢数据。
     * 按 MAX_BATCH_SIZE 切块逐块走 accept（限流/校验/入队语义逐块一致），聚合响应；
     * 拒收下标还原为原请求里的位置，客户端可按原始 body 定位坏数据。
     */
    public IngestResponse acceptChunked(TrustContext context, List<SpanEventDto> spans) {
        if (spans.size() <= MAX_BATCH_SIZE) {
            return accept(context, spans);
        }
        int accepted = 0, dropped = 0;
        List<IngestResponse.RejectedItem> rejected = new ArrayList<>();
        for (int from = 0; from < spans.size(); from += MAX_BATCH_SIZE) {
            List<SpanEventDto> chunk = spans.subList(from, Math.min(from + MAX_BATCH_SIZE, spans.size()));
            IngestResponse part = accept(context, chunk);
            accepted += part.getAcceptedCount();
            dropped += part.getDroppedInRequest();
            for (IngestResponse.RejectedItem item : part.getRejected()) {
                rejected.add(new IngestResponse.RejectedItem(item.getIndex() + from, item.getReason()));
            }
        }
        return new IngestResponse(accepted, rejected, dropped, droppedTotal.get());
    }

    /** 供 worker 微批写线程拉取：timeoutMs 为空队列时的最长等待，maxItems 为单批上限 */
    public List<SpanEnvelope> drainBatch(long timeoutMs, int maxItems) throws InterruptedException {
        List<SpanEnvelope> batch = new ArrayList<>(maxItems);
        SpanEnvelope first = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (first == null) {
            return batch;
        }
        batch.add(first);
        while (batch.size() < maxItems) {
            SpanEnvelope next = queue.poll();
            if (next == null) {
                break;
            }
            batch.add(next);
        }
        return batch;
    }

    /** 容量监控：队列水位 / 累计接收 / 累计丢弃 / 限流计数（/api/v1/ingest/stats） */
    public Map<String, Number> stats() {
        Map<String, Number> m = new LinkedHashMap<>();
        m.put("queueSize", queueSize());
        m.put("queueCapacity", queue.remainingCapacity() + queueSize());
        m.put("acceptedTotal", acceptedTotal.get());
        m.put("droppedTotal", droppedTotal.get());
        m.put("rateLimitedTotal", rateLimiter.getLimitedTotal());
        m.put("rateLimitedWorkspaces", rateLimiter.trackedWorkspaces());
        return m;
    }

    public int queueSize() {
        return queue.size();
    }

    public long getDroppedTotal() {
        return droppedTotal.get();
    }

    private String validate(SpanEventDto dto) {
        if (dto == null) {
            return "span is null";
        }
        if (dto.getTraceId() == null || dto.getTraceId().isEmpty()) {
            return "traceId must not be blank";
        }
        if (dto.getTraceId().length() > MAX_TRACE_ID) {
            return "traceId must be <= " + MAX_TRACE_ID + " chars";
        }
        if (dto.getSpanId() == null || dto.getSpanId().isEmpty()) {
            return "spanId must not be blank";
        }
        if (dto.getSpanId().length() > MAX_SPAN_ID) {
            return "spanId must be <= " + MAX_SPAN_ID + " chars";
        }
        if (dto.getParentSpanId() != null && dto.getParentSpanId().length() > MAX_SPAN_ID) {
            return "parentSpanId must be <= " + MAX_SPAN_ID + " chars";
        }
        if (dto.getAgentKey() == null || dto.getAgentKey().isEmpty()) {
            return "agentKey must not be blank";
        }
        if (dto.getAgentKey().length() > MAX_AGENT_KEY) {
            return "agentKey must be <= " + MAX_AGENT_KEY + " chars";
        }
        if (dto.getSessionId() != null && dto.getSessionId().length() > MAX_SESSION_ID) {
            return "sessionId must be <= " + MAX_SESSION_ID + " chars";
        }
        if (dto.getUserId() != null && dto.getUserId().length() > MAX_USER_ID) {
            return "userId must be <= " + MAX_USER_ID + " chars";
        }
        if (dto.getSpanType() == null || !ALLOWED_SPAN_TYPES.contains(dto.getSpanType())) {
            return "spanType must be one of " + ALLOWED_SPAN_TYPES;
        }
        if (parseTime(dto.getStartTime()) == null) {
            return "startTime must be ISO-8601";
        }
        if (dto.getEndTime() != null && !dto.getEndTime().isEmpty() && parseTime(dto.getEndTime()) == null) {
            return "endTime must be ISO-8601";
        }
        if (dto.getStatus() != null && !dto.getStatus().isEmpty()
                && !SpanEventDto.STATUS_OK.equals(dto.getStatus())
                && !SpanEventDto.STATUS_ERROR.equals(dto.getStatus())) {
            return "status must be OK or ERROR";
        }
        // 负数/溢出的 token 会让用量统计被脏数据拉低：整条拒绝并给出可定位原因
        if (!nonNegativeToken(dto.getInputTokens())
                || !nonNegativeToken(dto.getOutputTokens())
                || !nonNegativeToken(dto.getCachedInputTokens())) {
            return "tokens must be non-negative integers within Long range";
        }
        return null;
    }

    /** 负数 token 会让用量统计被脏数据拉低：入库前拦截（null 视为未上报，合法）。 */
    private boolean nonNegativeToken(Long value) {
        return value == null || value >= 0;
    }

    /** 序列化后的派生字段校验（长度按字符数保守估算，TEXT 上限 65535 字节）。 */
    private String validateEntity(SpanEventEntity entity) {
        if (entity.getAttributes() != null && entity.getAttributes().length() > MAX_ATTRIBUTES_CHARS) {
            return "attributes must be <= " + MAX_ATTRIBUTES_CHARS + " chars";
        }
        return null;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private SpanEventEntity buildEntity(TrustContext context, SpanEventDto dto) {
        SpanEventEntity entity = new SpanEventEntity();
        entity.setWorkspaceId(context.getWorkspaceId());
        entity.setAgentKey(dto.getAgentKey());
        entity.setAgentVersion(truncate(dto.getAgentVersion(), MAX_AGENT_VERSION));
        entity.setTraceId(dto.getTraceId());
        entity.setSpanId(dto.getSpanId());
        entity.setParentSpanId(dto.getParentSpanId());
        entity.setSpanType(dto.getSpanType());
        entity.setSpanName(truncate(dto.getSpanName(), MAX_SPAN_NAME));
        LocalDateTime start = parseTime(dto.getStartTime());
        LocalDateTime end = parseTime(dto.getEndTime());
        entity.setStartTime(start);
        entity.setEndTime(end);
        entity.setDurationMs(start != null && end != null && !end.isBefore(start)
                ? Duration.between(start, end).toMillis() : null);
        entity.setStatus(dto.getStatus() == null || dto.getStatus().isEmpty()
                ? SpanEventEntity.STATUS_OK : dto.getStatus());
        entity.setErrorType(truncate(dto.getErrorType(), MAX_ERROR_TYPE));
        entity.setModelName(truncate(dto.getModelName(), MAX_MODEL_NAME));
        entity.setToolName(truncate(dto.getToolName(), MAX_TOOL_NAME));
        entity.setInputTokens(dto.getInputTokens() == null ? 0L : dto.getInputTokens());
        entity.setOutputTokens(dto.getOutputTokens() == null ? 0L : dto.getOutputTokens());
        entity.setCachedInputTokens(dto.getCachedInputTokens() == null ? 0L : dto.getCachedInputTokens());
        entity.setAttributes(toJson(dto.getAttributes()));
        entity.setSessionId(dto.getSessionId());
        entity.setUserId(dto.getUserId());
        entity.setEnvironment(dto.getEnvironment() == null || dto.getEnvironment().isEmpty()
                ? "default" : truncate(dto.getEnvironment(), MAX_ENVIRONMENT));
        // REDACTED 模式下脱敏后内联存储（V2.0 ADR-302）
        String mode = properties.getContent().getCaptureMode();
        if ("REDACTED".equals(mode) || "FULL".equals(mode)) {
            int maxLen = properties.getContent().getMaxContentLength();
            if (dto.getInput() != null && !dto.getInput().isEmpty()) {
                entity.setInputText(redactionService.redact(dto.getInput(), maxLen));
            }
            if (dto.getOutput() != null && !dto.getOutput().isEmpty()) {
                entity.setOutputText(redactionService.redact(dto.getOutput(), maxLen));
            }
        }
        return entity;
    }

    private String toJson(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (Exception e) {
            log.warn("failed to serialize attributes: {}", e.getMessage());
            return null;
        }
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            // 带偏移量的 ISO 时间（Z / +08:00）归一到服务器时区：平台其余数据都是本地墙上
            // 时间（hooks 用 localtime、UI 按本地筛选），统一归 UTC 会让时间线整体偏移
            return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
