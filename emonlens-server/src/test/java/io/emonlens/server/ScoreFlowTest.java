package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Trust Score 端到端：Validation finding（CRITICAL）→ SCORE_CHANGED 事件 → 重算快照 →
 * score API 返回 CRITICAL（Critical Gate 生效）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-score;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ScoreFlowTest extends AbstractIntegrationTest {

    private static final String AGENT = "score-agent";

    @Test
    void criticalFindingDrivesScoreToCritical() throws Exception {
        // CRITICAL 级 Validation Monitor
        Map<String, Object> spec = new HashMap<>();
        spec.put("kind", "VALIDATION");
        spec.put("spanType", "tool");
        spec.put("where", "tool_name != null && tool_name == 'forbidden_tool'");
        Map<String, Object> monitorRequest = new HashMap<>();
        monitorRequest.put("agentKey", AGENT);
        monitorRequest.put("monitorKey", "forbidden-tool-gate");
        monitorRequest.put("kind", "VALIDATION");
        monitorRequest.put("severity", "CRITICAL");
        monitorRequest.put("dimension", "SECURITY");
        monitorRequest.put("spec", spec);
        postApi("/api/v1/monitors", monitorRequest);

        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span(AGENT, "score-trace-1", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        spans.add(span(AGENT, "score-trace-1", "t1", "root", "tool", "run_tool", "forbidden_tool", null, 2, 3));
        post("/api/v1/ingest/events", spans);

        // 等待评分快照（SCORE_CHANGED -> ScoreRecomputeHandler）
        JsonNode score = await(
                () -> {
                    try {
                        return readSafely(get("/api/v1/score/agents/" + AGENT)).path("data");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> !json.path("status").isMissingNode(),
                ASYNC_TIMEOUT_MS);
        assertThat(score.path("status").asText()).isEqualTo("CRITICAL");
        assertThat(score.path("scopeId").asText()).isEqualTo(AGENT);
        // Critical Gate 记录命中项
        assertThat(score.path("criticalGates").asText()).contains("MONITOR_VALIDATION");
        // overview 可见
        String overview = get("/api/v1/score/overview");
        assertThat(readSafely(overview).path("data").path(AGENT).path("status").asText()).isEqualTo("CRITICAL");
    }

    private JsonNode readSafely(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
