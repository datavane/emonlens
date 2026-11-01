package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.storage.entity.DatasetRunEntity;

/**
 * 模型不可用时的语义（M1/M3）：
 * - REPLAY 生成失败是执行错误 → ERROR，run=FAILED；
 * - TRACE 的 Judge 不可用是“无法判断” → UNKNOWN，不得当作 PASS，run 仍为执行完成。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-pilot-unavailable;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.dataset-run.pilot-mode=true",
        "emonlens.dataset-run.poll-interval-ms=200",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.eval.llm.base-url="})
@AutoConfigureMockMvc
class DatasetPilotUnavailableTest extends AbstractIntegrationTest {

    @Test
    void replayGenerationUnavailableIsErrorAndRunFailed() throws Exception {
        String datasetId = createDataset("unavailable-replay");
        addItem(datasetId, "q-unavailable", "A");
        String evaluatorId = createExactEvaluator("unavailable-replay-exact");

        JsonNode accepted = runAsync(datasetId, evaluatorId, "REPLAY", "unavailable-1");
        JsonNode detail = awaitRun(datasetId, accepted.path("data").path("runId").asText());
        JsonNode run = detail.path("data").path("run");
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.path("errorCount").asInt()).isEqualTo(1);
        JsonNode item = detail.path("data").path("items").get(0);
        assertThat(item.path("label").asText()).isEqualTo("ERROR");
        assertThat(item.path("resultDetails").path("calls").path("generation").asInt()).isZero();
        assertThat(item.path("reason").asText()).contains("not configured");
    }

    @Test
    void traceJudgeUnavailableIsUnknownNotPass() throws Exception {
        ingestTraceWithCompletion("unavailable-trace-1", "问题", "A");
        String datasetId = createDataset("unavailable-trace");
        Map<String, Object> item = new HashMap<>();
        item.put("traceId", "unavailable-trace-1");
        item.put("input", "问题");
        item.put("expectedOutput", "A");
        postApi("/api/v1/datasets/" + datasetId + "/items", item);

        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "LLM_JUDGE");
        spec.put("rubric", "回答必须正确");
        spec.put("referenceRequired", false);
        JsonNode evaluator = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", "unavailable-judge", "type", "LLM_JUDGE", "spec", spec)));
        String evaluatorId = evaluator.path("data").path("id").asText();

        JsonNode accepted = runAsync(datasetId, evaluatorId, "TRACE", "unavailable-2");
        JsonNode detail = awaitRun(datasetId, accepted.path("data").path("runId").asText());
        JsonNode run = detail.path("data").path("run");
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("unknownCount").asInt()).isEqualTo(1);
        assertThat(run.path("errorCount").asInt()).isZero();
        assertThat(run.path("coverage").asDouble()).isZero();
        JsonNode itemResult = detail.path("data").path("items").get(0);
        assertThat(itemResult.path("label").asText()).isEqualTo("UNKNOWN");
        assertThat(itemResult.path("resultDetails").path("reasonCode").asText())
                .isEqualTo("JUDGE_UNAVAILABLE");
        assertThat(itemResult.path("resultDetails").path("calls").path("judge").asInt()).isZero();
    }

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private void addItem(String datasetId, String input, String expectedOutput) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("input", input, "expectedOutput", expectedOutput)));
        assertThat(response.path("code").asInt()).isZero();
    }

    private String createExactEvaluator(String key) throws Exception {
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "EXPRESSION");
        spec.put("passWhen", "reference_match.exact(candidate_output, expected_output)");
        spec.put("referenceRequired", true);
        spec.put("preset", "reference-exact-match");
        JsonNode response = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", key, "type", "EXPRESSION", "spec", spec)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private JsonNode runAsync(String datasetId, String evaluatorId, String mode, String key) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                Map.of("evaluatorId", evaluatorId, "mode", mode))))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(202);
        return readTree(response.getContentAsString(StandardCharsets.UTF_8));
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
                    String status = json.path("data").path("run").path("status").asText();
                    return !DatasetRunEntity.STATUS_PENDING.equals(status)
                            && !DatasetRunEntity.STATUS_RUNNING.equals(status);
                },
                60000);
    }

    private void ingestTraceWithCompletion(String traceId, String input, String completion) throws Exception {
        List<Map<String, Object>> spans = new ArrayList<>();
        Map<String, Object> root = span("unavailable-agent", traceId, "root", null,
                "agent", "invoke_agent", null, null, 1, 5);
        Map<String, Object> llm = span("unavailable-agent", traceId, "llm", "root",
                "llm", "call_model", null, "pilot-model", 2, 4);
        llm.put("content", Map.of("prompt", input, "completion", completion));
        spans.add(root);
        spans.add(llm);
        post("/api/v1/ingest/events", spans);
        await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + traceId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    try {
                        return "COMPLETED".equals(readTree(json).path("data")
                                .path("summary").path("status").asText());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                ASYNC_TIMEOUT_MS);
    }
}
