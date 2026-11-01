package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.engine.eval.LlmClient;
import io.emonlens.server.engine.eval.LlmUnavailableException;

/**
 * 评测缓存：同样本 + 同评估器 + 同模板/模型时命中；候选内容变化时失效。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-eval-cache;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.dataset-run.pilot-mode=true",
        "emonlens.dataset-run.poll-interval-ms=200",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.eval.cache-enabled=true",
        "emonlens.eval.cache-ttl-hours=24",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class EvalCacheFlowTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    static class FakeLlm implements LlmClient {
        final List<String[]> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger judgeCalls = new AtomicInteger();
        volatile String output = "ok";
        volatile boolean judgeUnavailable = false;

        @Override
        public String chat(String systemPrompt, String userPrompt, String model, double temperature) {
            return chatWithUsage(systemPrompt, userPrompt, model, temperature).content();
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt,
                                       String model, double temperature) {
            calls.add(new String[] {systemPrompt, userPrompt});
            if (systemPrompt != null && systemPrompt.contains("You are an evaluator")) {
                judgeCalls.incrementAndGet();
                if (judgeUnavailable) {
                    throw new LlmUnavailableException("llm not configured");
                }
                return new ChatReply("{\"score\":8,\"confidence\":0.9,\"reason\":\"ok\"}", 11, 22);
            }
            return new ChatReply(output, 7, 13);
        }
    }

    @javax.annotation.Resource
    private FakeLlm fakeLlm;

    @BeforeEach
    void reset() {
        fakeLlm.output = "ok";
        fakeLlm.judgeCalls.set(0);
        fakeLlm.calls.clear();
        fakeLlm.judgeUnavailable = false;
    }

    @Test
    void secondRunHitsCacheAndCandidateChangeMisses() throws Exception {
        String datasetId = createDataset("cache-ds-" + System.nanoTime());
        addItem(datasetId, "cache-q", "ok");
        String evaluatorId = createEvaluator("cache-judge",
                Map.of("rubric", "回答必须正确", "minScore", 6, "referenceRequired", false));

        String run1 = runReplay(datasetId, evaluatorId, "cache-run-1");
        JsonNode detail1 = awaitRun(datasetId, run1);
        assertThat(detail1.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(fakeLlm.judgeCalls.get()).isEqualTo(1);
        JsonNode result1 = detail1.path("data").path("items").get(0)
                .path("evaluatorResults").get(0);
        assertThat(result1.path("cacheHit").asBoolean()).isFalse();
        assertThat(result1.path("judgeCalled").asBoolean()).isTrue();

        String run2 = runReplay(datasetId, evaluatorId, "cache-run-2");
        JsonNode detail2 = awaitRun(datasetId, run2);
        assertThat(fakeLlm.judgeCalls.get()).isEqualTo(1); // 第二次没有 Judge 调用
        JsonNode result2 = detail2.path("data").path("items").get(0)
                .path("evaluatorResults").get(0);
        assertThat(result2.path("cacheHit").asBoolean()).isTrue();
        assertThat(result2.path("judgeCalled").asBoolean()).isFalse();
        assertThat(result2.path("label").asText()).isEqualTo("PASS");

        JsonNode tasks = readTree(get("/api/v1/datasets/" + datasetId
                + "/runs/" + run2 + "/tasks"));
        assertThat(tasks.path("data").path("records").get(0).path("cacheKey").asText())
                .hasSize(64);

        JsonNode cost = readTree(get("/api/v1/datasets/" + datasetId
                + "/runs/" + run2 + "/cost")).path("data");
        assertThat(cost.path("cache").path("hits").asInt()).isEqualTo(1);
        assertThat(cost.path("cache").path("judgeCalls").asInt()).isZero();
        assertThat(cost.path("cache").path("judgeEvaluations").asInt()).isEqualTo(1);
        assertThat(cost.path("cache").path("hitRate").asDouble()).isEqualTo(100.0);

        // 候选输出变化 → key 变化 → 必须重新调用 Judge
        fakeLlm.output = "ok-v2";
        String run3 = runReplay(datasetId, evaluatorId, "cache-run-3");
        JsonNode detail3 = awaitRun(datasetId, run3);
        assertThat(fakeLlm.judgeCalls.get()).isEqualTo(2);
        assertThat(detail3.path("data").path("items").get(0)
                .path("evaluatorResults").get(0).path("cacheHit").asBoolean()).isFalse();
    }

    @Test
    void unavailableJudgeIsNotCached() throws Exception {
        String datasetId = createDataset("cache-unavailable-" + System.nanoTime());
        addItem(datasetId, "cache-unavailable-q", "ok");
        String evaluatorId = createEvaluator("cache-judge-unavailable",
                Map.of("rubric", "回答必须正确", "minScore", 6, "referenceRequired", false));

        fakeLlm.judgeUnavailable = true;
        String run1 = runReplay(datasetId, evaluatorId, "cache-unavailable-run-1");
        JsonNode detail1 = awaitRun(datasetId, run1);
        JsonNode result1 = detail1.path("data").path("items").get(0)
                .path("evaluatorResults").get(0);
        assertThat(result1.path("label").asText()).isEqualTo("UNKNOWN");
        assertThat(result1.path("reasonCode").asText()).isEqualTo("JUDGE_UNAVAILABLE");
        assertThat(result1.path("cacheHit").asBoolean()).isFalse();

        // LLM 恢复后必须重新调用，而不是命中上一条 JUDGE_UNAVAILABLE
        fakeLlm.judgeUnavailable = false;
        String run2 = runReplay(datasetId, evaluatorId, "cache-unavailable-run-2");
        JsonNode detail2 = awaitRun(datasetId, run2);
        JsonNode result2 = detail2.path("data").path("items").get(0)
                .path("evaluatorResults").get(0);
        assertThat(fakeLlm.judgeCalls.get()).isEqualTo(2);
        assertThat(result2.path("cacheHit").asBoolean()).isFalse();
        assertThat(result2.path("label").asText()).isEqualTo("PASS");
    }

    // ---------- helpers ----------

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private void addItem(String datasetId, String input, String expected) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("input", input, "expectedOutput", expected)));
        assertThat(response.path("code").asInt()).isZero();
    }

    private String createEvaluator(String key, Map<String, Object> fields) throws Exception {
        Map<String, Object> spec = new HashMap<>(fields);
        spec.put("type", "LLM_JUDGE");
        JsonNode response = readTree(postApi("/api/v1/evaluators",
                Map.of("evaluatorKey", key, "type", "LLM_JUDGE", "spec", spec)));
        assertThat(response.path("code").asInt()).as(response.toString()).isZero();
        return response.path("data").path("id").asText();
    }

    private String runReplay(String datasetId, String evaluatorId, String key) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("evaluatorId", evaluatorId);
        body.put("mode", "REPLAY");
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(202);
        JsonNode json = readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(json.path("code").asInt()).isZero();
        return json.path("data").path("runId").asText();
    }

    private JsonNode awaitRun(String datasetId, String runId) throws Exception {
        return await(
                () -> {
                    try {
                        return readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    String state = json.path("data").path("run").path("status").asText();
                    return !"PENDING".equals(state) && !"RUNNING".equals(state);
                },
                ASYNC_TIMEOUT_MS);
    }
}
