package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.engine.eval.LlmClient;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-workspace-quota;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.dataset-run.pilot-mode=false",
        "emonlens.dataset-run.poll-interval-ms=100",
        "emonlens.dataset-run.max-active-runs=2",
        "emonlens.dataset-run.max-active-runs-per-workspace=1",
        "emonlens.dataset-run.worker-threads=1",
        "emonlens.eval.auto-run-enabled=false"})
@AutoConfigureMockMvc
class DatasetRunWorkspaceQuotaTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class BlockingLlmConfig {
        @Bean
        @Primary
        BlockingLlm blockingLlm() {
            return new BlockingLlm();
        }
    }

    static class BlockingLlm implements LlmClient {
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String chat(String systemPrompt, String userPrompt, String model, double temperature) {
            return chatWithUsage(systemPrompt, userPrompt, model, temperature).content();
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt,
                                       String model, double temperature) {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ChatReply("{\"score\":9,\"confidence\":0.9,\"reason\":\"ok\"}", 1, 1);
        }
    }

    @Resource
    private BlockingLlm blockingLlm;

    @Test
    void enforcesPerWorkspaceQuotaEvenWhenGlobalCapacityRemains() throws Exception {
        String datasetId = createDataset("workspace-quota-" + System.nanoTime());
        addItem(datasetId, "q-workspace-quota", "ok");
        String evaluatorId = createEvaluator("workspace-quota-judge", Map.of(
                "rubric", "回答必须正确", "minScore", 6, "referenceRequired", false));

        JsonNode first = runAsync(datasetId, evaluatorId, "ws-run-1", 202);
        String firstId = first.path("data").path("runId").asText();
        await(() -> readRun(datasetId, firstId),
                json -> "RUNNING".equals(json.path("data").path("run").path("status").asText()),
                20000);

        MockHttpServletResponse rejected = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", "ws-run-2")
                        .contentType("application/json")
                        .content("{\"evaluatorId\":\"" + evaluatorId + "\",\"mode\":\"REPLAY\"}"))
                .andReturn().getResponse();
        assertThat(rejected.getStatus()).isEqualTo(429);

        blockingLlm.release.countDown();
        JsonNode firstDone = awaitRun(datasetId, firstId);
        assertThat(firstDone.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");

        JsonNode third = runAsync(datasetId, evaluatorId, "ws-run-3", 202);
        JsonNode thirdDone = awaitRun(datasetId, third.path("data").path("runId").asText());
        assertThat(thirdDone.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
    }

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private void addItem(String datasetId, String input, String expectedOutput) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("input", input);
        body.put("expectedOutput", expectedOutput);
        assertThat(readTree(postApi("/api/v1/datasets/" + datasetId + "/items", body))
                .path("code").asInt()).isZero();
    }

    private String createEvaluator(String key, Map<String, Object> spec) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", key, "type", "LLM_JUDGE", "spec", spec)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private JsonNode runAsync(String datasetId, String evaluatorId, String key, int expectedStatus)
            throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"evaluatorId\":\"" + evaluatorId + "\",\"mode\":\"REPLAY\"}"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("async run status: "
                + response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(expectedStatus);
        return readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode awaitRun(String datasetId, String runId) throws Exception {
        return await(() -> readRun(datasetId, runId),
                json -> {
                    String status = json.path("data").path("run").path("status").asText();
                    return !"PENDING".equals(status) && !"RUNNING".equals(status);
                }, 60000);
    }

    private JsonNode readRun(String datasetId, String runId) {
        try {
            return readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
