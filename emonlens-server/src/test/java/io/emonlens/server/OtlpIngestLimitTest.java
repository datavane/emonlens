package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * OTLP 接入的请求体上限：解析前拒收超大批次（防内存放大），小批次正常入库。
 * OTLP 端点收原始 JSON 文本，直接发 raw body，不走会把 String 再序列化一层的 post() 助手。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-otlp-limit;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.otlp.max-body-chars=1000"})
@AutoConfigureMockMvc
class OtlpIngestLimitTest extends AbstractIntegrationTest {

    private JsonNode postOtlp(String body) throws Exception {
        String response = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/ingest/otlp/v1/traces")
                        .header("X-Trust-Key", INGEST_KEY)
                        .contentType("application/json")
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return readTree(response);
    }

    @Test
    void rejectsOversizedOtlpBodyBeforeParsing() throws Exception {
        String oversized = "{\"resourceSpans\":[],\"padding\":\"" + "x".repeat(2000) + "\"}";
        JsonNode response = postOtlp(oversized);
        assertThat(response.path("code").asInt()).isEqualTo(40000);
        assertThat(response.path("message").asText()).contains("OTLP payload too large");
        assertThat(response.path("message").asText()).contains("max-body-chars");
    }

    @Test
    void acceptsSmallOtlpBody() throws Exception {
        String body = "{\"resourceSpans\":[{\"resource\":{\"attributes\":["
                + "{\"key\":\"service.name\",\"value\":{\"stringValue\":\"limit-svc\"}}]},"
                + "\"scopeSpans\":[{\"spans\":[{"
                + "\"traceId\":\"trace-lim-01\",\"spanId\":\"span01\",\"parentSpanId\":\"\","
                + "\"name\":\"run\",\"startTimeUnixNano\":\"1756531200000000000\","
                + "\"endTimeUnixNano\":\"1756531201000000000\","
                + "\"attributes\":[{\"key\":\"trust.agent.id\",\"value\":{\"stringValue\":\"limit-agent\"}},"
                + "{\"key\":\"trust.span.type\",\"value\":{\"stringValue\":\"agent\"}}]"
                + "}]}]}]}";
        JsonNode response = postOtlp(body);
        assertThat(response.path("code").asInt()).as(response.toString()).isZero();
        assertThat(response.path("data").path("acceptedCount").asInt()).as(response.toString()).isEqualTo(1);
    }
}
