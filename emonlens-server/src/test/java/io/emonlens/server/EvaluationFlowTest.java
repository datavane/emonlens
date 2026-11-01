package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.engine.eval.LlmClient;

/**
 * Evaluation Engine 端到端：LLM_JUDGE evaluator（Fake LLM）→ 自动采样评估 → eval-result →
 * EVALUATION Monitor 阈值告警 → Finding。
 * Fake LLM 固定返回低分（2.0），验证 FAIL 判定与 pass_rate 越限告警。
 */
@SpringBootTest(properties = {
        "emonlens.eval.default-rate=1.0",
        "emonlens.eval.max-attempt=2",
        // 独立 H2 库：避免与其他测试上下文残留的 poller 抢事件（其采样率不同）
        "spring.datasource.url=jdbc:h2:mem:emonlens-eval;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class EvaluationFlowTest extends AbstractIntegrationTest {

    private static final String AGENT = "eval-agent";

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        LlmClient fakeLlmClient() {
            return (systemPrompt, userPrompt, model, temperature) -> {
                if (userPrompt.contains("解析失败")) {
                    return "MOCK-ECHO: 这不是 JSON，模型没按格式作答";
                }
                return "{\"score\": 2.0, \"confidence\": 0.9, \"reason\": \"rubric violated\"}";
            };
        }
    }

    @Test
    void llmJudgeToEvalResultToEvaluationMonitorFinding() throws Exception {
        // 1. 创建 LLM_JUDGE evaluator
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "LLM_JUDGE");
        spec.put("rubric", "回答必须礼貌且正确");
        spec.put("minScore", 6.0);
        Map<String, Object> createRequest = new HashMap<>();
        createRequest.put("evaluatorKey", "answer-quality");
        createRequest.put("type", "LLM_JUDGE");
        createRequest.put("spec", spec);
        String created = postApi("/api/v1/evaluators", createRequest);
        assertThat(readTree(created).path("code").asInt()).isZero();

        // 2. ingest trace1（completed）→ 自动采样评估 → FAIL（fake 固定 2.0 < 6.0）
        List<Map<String, Object>> spans1 = new ArrayList<>();
        spans1.add(span(AGENT, "eval-trace-1", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        spans1.add(span(AGENT, "eval-trace-1", "llm", "root", "llm", "call_model", null, "approved-model-a", 2, 4));
        post("/api/v1/ingest/events", spans1);

        String results = await(
                () -> {
                    try {
                        return get("/api/v1/eval-results?agentKey=" + AGENT + "&evaluatorKey=answer-quality");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("total").asLong() >= 1,
                ASYNC_TIMEOUT_MS);
        JsonNode evalResult = readSafely(results).path("data").path("records").get(0);
        assertThat(evalResult.path("label").asText()).isEqualTo("FAIL");
        assertThat(evalResult.path("score").asDouble()).isEqualTo(2.0);

        // 全量统计接口：通过率 KPI / 趋势不再由“最新 500 条”近似
        JsonNode stats = readSafely(get("/api/v1/eval-results/stats?agentKey=" + AGENT
                + "&evaluatorKey=answer-quality")).path("data");
        assertThat(stats.path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.path("fail").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(stats.path("daily").isArray()).isTrue();

        // 3. 创建 EVALUATION monitor：pass_rate < 0.5 告警（当前 pass_rate=0）
        Map<String, Object> monitorSpec = new HashMap<>();
        monitorSpec.put("evaluatorKey", "answer-quality");
        monitorSpec.put("metric", "pass_rate");
        monitorSpec.put("op", "<");
        monitorSpec.put("value", 0.5);
        monitorSpec.put("windowCount", 10);
        Map<String, Object> monitorRequest = new HashMap<>();
        monitorRequest.put("agentKey", AGENT);
        monitorRequest.put("monitorKey", "answer-quality-gate");
        monitorRequest.put("kind", "EVALUATION");
        monitorRequest.put("spec", monitorSpec);
        monitorRequest.put("severity", "HIGH");
        assertThat(readTree(postApi("/api/v1/monitors", monitorRequest)).path("code").asInt()).isZero();

        // 4. ingest trace2 → 检测时评估监控命中 → MONITOR_EVALUATION Finding
        List<Map<String, Object>> spans2 = new ArrayList<>();
        spans2.add(span(AGENT, "eval-trace-2", "root2", null, "agent", "invoke_agent", null, null, 1, 5));
        post("/api/v1/ingest/events", spans2);

        String findings = await(
                () -> {
                    try {
                        return get("/api/v1/findings?agentKey=" + AGENT + "&type=MONITOR_EVALUATION");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("total").asLong() >= 1,
                ASYNC_TIMEOUT_MS);
        JsonNode finding = readSafely(findings).path("data").path("records").get(0);
        assertThat(finding.path("detectorId").asText()).isEqualTo("answer-quality-gate");
        assertThat(finding.path("message").asText()).contains("pass_rate");
    }

    @Test
    void llmJudgeUnparseableOutputIsUnavailableNotSilentFail() throws Exception {
        Map<String, Object> spec = new HashMap<>();
        spec.put("rubric", "解析失败用例");
        spec.put("minScore", 6.0);
        Map<String, Object> req = new HashMap<>();
        req.put("evaluatorKey", "unparseable-judge");
        req.put("type", "LLM_JUDGE");
        req.put("spec", spec);
        assertThat(readTree(postApi("/api/v1/evaluators", req)).path("code").asInt()).isZero();

        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span(AGENT, "eval-trace-unparseable", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        post("/api/v1/ingest/events", spans);

        String results = await(
                () -> {
                    try {
                        return get("/api/v1/eval-results?agentKey=" + AGENT + "&evaluatorKey=unparseable-judge");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("total").asLong() >= 1,
                ASYNC_TIMEOUT_MS);
        JsonNode rec = readSafely(results).path("data").path("records").get(0);
        // 缺数字 score → EVAL_UNAVAILABLE 且 reason 保留原始输出；绝不能兜底成 0 分 FAIL
        assertThat(rec.path("label").asText()).isEqualTo("EVAL_UNAVAILABLE");
        assertThat(rec.path("reason").asText()).contains("invalid judge output").contains("MOCK-ECHO");
    }

    private JsonNode readSafely(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
