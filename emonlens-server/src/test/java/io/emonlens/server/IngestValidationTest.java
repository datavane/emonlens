package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 落库列宽保护：坏数据单行拒收/截断，不能连累同批其他 span。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-ingest-validation;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class IngestValidationTest extends AbstractIntegrationTest {

    @Test
    void rejectsOverlongIdentifiersWithoutDroppingValidSpans() throws Exception {
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span("val-agent", "val-trace-ok", "root", null, "agent", "run", null, null, 1, 2));
        spans.add(span("val-agent", "x".repeat(65), "bad-root", null, "agent", "run", null, null, 1, 2));

        JsonNode response = readTree(post("/api/v1/ingest/events", spans));
        JsonNode data = response.path("data");
        assertThat(data.path("acceptedCount").asInt()).isEqualTo(1);
        assertThat(data.path("rejected").size()).isEqualTo(1);
        assertThat(data.path("rejected").get(0).path("reason").asText())
                .contains("traceId must be <= 64 chars");
    }

    @Test
    void truncatesOverlongErrorTypeInsteadOfDroppingSpan() throws Exception {
        Map<String, Object> span = span("val-agent", "val-trace-error", "root", null,
                "agent", "run", null, null, 1, 2);
        span.put("status", "ERROR");
        span.put("errorType", "E".repeat(400));
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span);

        JsonNode response = readTree(post("/api/v1/ingest/events", spans));
        assertThat(response.path("data").path("acceptedCount").asInt()).isEqualTo(1);

        JsonNode detail = await(() -> {
                    try {
                        return readTree(get("/api/v1/traces/val-trace-error"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> json.path("data").path("summary").path("status").asText()
                        .equals("COMPLETED"),
                ASYNC_TIMEOUT_MS);
        JsonNode spansNode = detail.path("data").path("spans");
        assertThat(spansNode.size()).isEqualTo(1);
        assertThat(spansNode.get(0).path("errorType").asText()).hasSize(128);
    }
}
