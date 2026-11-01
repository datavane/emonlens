package io.emonlens.server.engine.otlp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.ingest.dto.SpanEventDto;

/**
 * OTLP-JSON 映射：trust. 与 gen_ai. 语义键、span_type 推导、时间戳归一化。
 */
class OtlpJsonMapperTest {

    private final OtlpJsonMapper mapper = new OtlpJsonMapper(new ObjectMapper());

    private static final String BODY = "{\"resourceSpans\":[{\"resource\":{\"attributes\":["
            + "{\"key\":\"service.name\",\"value\":{\"stringValue\":\"support-service\"}},"
            + "{\"key\":\"trust.agent.version\",\"value\":{\"stringValue\":\"v37\"}}]},"
            + "\"scopeSpans\":[{\"spans\":[{"
            + "\"traceId\":\"abc123\",\"spanId\":\"span01\",\"parentSpanId\":\"\","
            + "\"name\":\"call_model\","
            + "\"startTimeUnixNano\":\"1756531200000000000\","
            + "\"endTimeUnixNano\":\"1756531202000000000\","
            + "\"status\":{\"code\":2,\"message\":\"timeout\"},"
            + "\"attributes\":["
            + "{\"key\":\"trust.agent.id\",\"value\":{\"stringValue\":\"support-agent\"}},"
            + "{\"key\":\"trust.span.type\",\"value\":{\"stringValue\":\"llm\"}},"
            + "{\"key\":\"gen_ai.request.model\",\"value\":{\"stringValue\":\"approved-model-a\"}},"
            + "{\"key\":\"gen_ai.usage.input_tokens\",\"value\":{\"intValue\":120}},"
            + "{\"key\":\"gen_ai.usage.output_tokens\",\"value\":{\"intValue\":80}},"
            + "{\"key\":\"custom.long.tail\",\"value\":{\"stringValue\":\"keep\"}}"
            + "]}]}]}]}";

    @Test
    void mapsOtlpJsonToSpanDtos() {
        List<SpanEventDto> spans = mapper.mapToSpanDtos(BODY);
        assertThat(spans).hasSize(1);
        SpanEventDto span = spans.get(0);
        assertThat(span.getTraceId()).isEqualTo("abc123");
        assertThat(span.getSpanType()).isEqualTo("llm");
        assertThat(span.getAgentKey()).isEqualTo("support-agent");
        assertThat(span.getAgentVersion()).isEqualTo("v37");
        assertThat(span.getModelName()).isEqualTo("approved-model-a");
        assertThat(span.getStatus()).isEqualTo("ERROR");
        assertThat(span.getErrorType()).isEqualTo("timeout");
        assertThat(span.getInputTokens()).isEqualTo(120L);
        assertThat(span.getOutputTokens()).isEqualTo(80L);
        // Unix 纳秒 → 服务器时区墙上时间（与 hooks/本地时间轴一致）
        assertThat(span.getStartTime()).isNotNull();
        assertThat(java.time.LocalDateTime.parse(span.getEndTime()))
                .isEqualTo(java.time.LocalDateTime.parse(span.getStartTime()).plusSeconds(2));
        // 长尾属性保留
        assertThat(span.getAttributes()).containsEntry("custom.long.tail", "keep");
    }

    @Test
    void spanTypeDerivesFromAttributes() {
        String toolBody = BODY
                .replace("\"trust.span.type\",\"value\":{\"stringValue\":\"llm\"}",
                        "\"trust.tool.name\",\"value\":{\"stringValue\":\"risk_query\"}")
                .replace("{\"key\":\"gen_ai.request.model\",\"value\":{\"stringValue\":\"approved-model-a\"}},", "")
                .replace("\"traceId\":\"abc123\"", "\"traceId\":\"abc456\"");
        List<SpanEventDto> spans = mapper.mapToSpanDtos(toolBody);
        assertThat(spans.get(0).getSpanType()).isEqualTo("tool");
        assertThat(spans.get(0).getToolName()).isEqualTo("risk_query");
    }

    @Test
    void invalidBodyThrowsBadRequest() {
        // 解析失败必须显式报错：静默空列表会让采集端误以为上报成功，数据无声丢失
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mapper.mapToSpanDtos("not-json"))
                .isInstanceOf(io.emonlens.common.EmonLensException.class);
        assertThat(mapper.mapToSpanDtos(null)).isEmpty();
        assertThat(mapper.mapToSpanDtos("")).isEmpty();
    }
}
