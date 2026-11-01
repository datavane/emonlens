package io.emonlens.server.engine.otlp;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.ingest.dto.SpanEventDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * OTLP/HTTP-JSON ExportTraceServiceRequest -> SpanEventDto 映射。
 * 语义：trust.* 平台属性 + gen_ai.* 标准属性（V1.1 §6）；
 * span_type 推导优先 trust.span.type，其次按属性特征（gen_ai→llm、trust.tool.name→tool）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtlpJsonMapper {

    private final ObjectMapper objectMapper;

    public List<SpanEventDto> mapToSpanDtos(String body) {
        List<SpanEventDto> result = new ArrayList<>();
        JsonNode root;
        try {
            root = objectMapper.readTree(body == null || body.isEmpty() ? "{}" : body);
        } catch (Exception e) {
            // 解析失败静默返回空列表会让采集端以为上报成功（200 accepted:0），数据无声丢失
            log.warn("otlp body unparseable: {}", e.getMessage());
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "otlp body is not valid JSON: " + e.getMessage());
        }
        for (JsonNode resourceSpans : root.path("resourceSpans")) {
            Map<String, Object> resource = attributes(resourceSpans.path("resource").path("attributes"));
            for (JsonNode scopeSpans : resourceSpans.path("scopeSpans")) {
                for (JsonNode span : scopeSpans.path("spans")) {
                    SpanEventDto dto = mapSpan(resource, span);
                    if (dto != null) {
                        result.add(dto);
                    }
                }
            }
        }
        return result;
    }

    private SpanEventDto mapSpan(Map<String, Object> resource, JsonNode span) {
        String traceId = span.path("traceId").asText(null);
        String spanId = span.path("spanId").asText(null);
        if (traceId == null || spanId == null) {
            return null;
        }
        SpanEventDto dto = new SpanEventDto();
        dto.setTraceId(traceId);
        dto.setSpanId(spanId);
        String parent = span.path("parentSpanId").asText("");
        if (!parent.isEmpty()) {
            dto.setParentSpanId(parent);
        }
        // DTO 时间为 ISO-8601 字符串，由 ingest 层解析归一化
        LocalDateTime start = fromUnixNano(span.path("startTimeUnixNano").asText("0"));
        if (start != null) {
            dto.setStartTime(start.toString());
        }
        LocalDateTime end = fromUnixNano(span.path("endTimeUnixNano").asText("0"));
        if (end != null) {
            dto.setEndTime(end.toString());
        }
        Map<String, Object> attrs = attributes(span.path("attributes"));

        // agent 语义
        dto.setAgentKey(stringValue(attrs, "trust.agent.id",
                stringValue(attrs, "trust.agent.name", resource.get("service.name"))));

        // span type：trust.span.type > 特征推导 > 默认 workflow
        String spanType = stringValue(attrs, "trust.span.type", null);
        if (spanType == null) {
            if (attrs.containsKey("gen_ai.request.model") || attrs.containsKey("gen_ai.response.model")) {
                spanType = "llm";
            } else if (attrs.containsKey("trust.tool.name")) {
                spanType = "tool";
            } else if (attrs.containsKey("trust.context.asset_ids")) {
                spanType = "retrieval";
            } else {
                spanType = parent.isEmpty() ? "agent" : "workflow";
            }
        }
        dto.setSpanType(spanType);
        dto.setSpanName(span.path("name").asText(null));
        dto.setAgentVersion(stringValue(attrs, "trust.agent.version", resource.get("trust.agent.version")));
        dto.setModelName(stringValue(attrs, "gen_ai.request.model",
                stringValue(attrs, "gen_ai.response.model", null)));
        dto.setToolName(stringValue(attrs, "trust.tool.name", null));
        int statusCode = span.path("status").path("code").asInt(0);
        String statusMessage = span.path("status").path("message").asText("");
        dto.setStatus(statusCode == 2 ? SpanEventDto.STATUS_ERROR : SpanEventDto.STATUS_OK);
        dto.setErrorType(statusMessage.isEmpty() ? null : statusMessage);

        // token 语义（gen_ai.usage.*）
        Number inputTokens = numberValue(attrs, "gen_ai.usage.input_tokens",
                numberValue(attrs, "llm.usage.prompt_tokens", null));
        Number outputTokens = numberValue(attrs, "gen_ai.usage.output_tokens",
                numberValue(attrs, "llm.usage.completion_tokens", null));
        if (inputTokens != null) {
            dto.setInputTokens(inputTokens.longValue());
        }
        if (outputTokens != null) {
            dto.setOutputTokens(outputTokens.longValue());
        }

        // 去掉已被提升为列的长尾键后存 attributes
        Map<String, Object> longTail = new HashMap<>(attrs);
        longTail.remove("trust.agent.id");
        longTail.remove("trust.agent.name");
        longTail.remove("trust.agent.version");
        longTail.remove("trust.span.type");
        longTail.remove("trust.tool.name");
        longTail.remove("gen_ai.request.model");
        longTail.remove("gen_ai.response.model");
        longTail.remove("gen_ai.usage.input_tokens");
        longTail.remove("gen_ai.usage.output_tokens");
        if (!longTail.isEmpty()) {
            dto.setAttributes(longTail);
        }
        return dto;
    }

    /** OTLP attributes 数组 -> map（取 stringValue/intValue/doubleValue/boolValue） */
    Map<String, Object> attributes(JsonNode attributes) {
        Map<String, Object> map = new HashMap<>();
        if (!attributes.isArray()) {
            return map;
        }
        for (JsonNode entry : attributes) {
            String key = entry.path("key").asText(null);
            if (key == null) {
                continue;
            }
            JsonNode value = entry.path("value");
            if (value.has("stringValue")) {
                map.put(key, value.path("stringValue").asText());
            } else if (value.has("intValue")) {
                map.put(key, value.path("intValue").asLong());
            } else if (value.has("doubleValue")) {
                map.put(key, value.path("doubleValue").asDouble());
            } else if (value.has("boolValue")) {
                map.put(key, value.path("boolValue").asBoolean());
            }
        }
        return map;
    }

    private static String stringValue(Map<String, Object> attrs, String key, String fallback) {
        Object value = attrs.get(key);
        return value != null ? String.valueOf(value) : fallback;
    }

    private static String stringValue(Map<String, Object> attrs, String key, Object fallback) {
        Object value = attrs.get(key);
        return value != null ? String.valueOf(value) : (fallback != null ? String.valueOf(fallback) : null);
    }

    private static Number numberValue(Map<String, Object> attrs, String key, Number fallback) {
        Object value = attrs.get(key);
        return value instanceof Number ? (Number) value : fallback;
    }

    static LocalDateTime fromUnixNano(String nano) {
        try {
            long ns = Long.parseLong(nano);
            // Unix 纳秒是绝对时刻，转墙上时间用服务器时区（与 hooks/UI 的本地时间轴一致）
            return Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
