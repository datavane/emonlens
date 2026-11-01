package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 每 workspace ingest 令牌桶限流：
 * 1) 额度内正常受理；2) 额度耗尽整批拒绝（HTTP 429 + code 50301 + Retry-After）；
 * 3) stats 暴露限流计数；4) 队列不受被拒批次影响。
 */
@SpringBootTest(properties = {
        "emonlens.ingest.rate-limit-spans-per-minute=3",
        "spring.datasource.url=jdbc:h2:mem:emonlens-ingest-rate;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class IngestRateLimitTest extends AbstractIntegrationTest {

    private static final String AGENT = "rate-agent";

    @Test
    void spanBudgetIsEnforcedPerWorkspace() throws Exception {
        // 1) 额度内：3 个 span 全部受理
        String traceId = "rate-trace-001";
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span(AGENT, traceId, "r-1", null, "agent", "invoke_agent", null, null, 1, 10));
        spans.add(span(AGENT, traceId, "r-2", "r-1", "llm", "call_model", null, "m1", 2, 8));
        spans.add(span(AGENT, traceId, "r-3", "r-1", "tool", "call_tool", "search", null, 3, 6));
        String ok = post("/api/v1/ingest/events", spans);
        JsonNode okJson = readTree(ok);
        assertThat(okJson.path("code").asInt()).isZero();
        assertThat(okJson.path("data").path("acceptedCount").asInt()).isEqualTo(3);

        // 2) 额度耗尽：第 4 个 span 整批拒绝，HTTP 429 + Retry-After
        var limitedResult = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/ingest/events")
                        .header("Authorization", adminAuth())
                        .header("X-Trust-Key", INGEST_KEY)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(List.of(
                                span(AGENT, traceId, "r-4", "r-1", "llm", "call_model", null, "m1", 4, 8)))))
                .andReturn().getResponse();
        assertThat(limitedResult.getStatus()).isEqualTo(429);
        assertThat(limitedResult.getHeader("Retry-After")).isNotBlank();
        JsonNode limited = readTree(limitedResult.getContentAsString());
        assertThat(limited.path("code").asInt()).isEqualTo(50301);
        assertThat(limited.path("message").asText()).contains("rate limited");

        // 3) stats（/observations/stats）：限流计数 + 累计受理（后台 worker 可能已把队列排空，故断言累计值）
        String stats = get("/api/v1/observations/stats");
        JsonNode statsJson = readTree(stats).path("data");
        assertThat(statsJson.path("rateLimitedTotal").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(statsJson.path("acceptedTotal").asLong()).isGreaterThanOrEqualTo(3);
    }
}
