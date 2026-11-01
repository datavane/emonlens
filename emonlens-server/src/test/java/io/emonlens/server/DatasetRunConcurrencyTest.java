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
        "spring.datasource.url=jdbc:h2:mem:emonlens-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.dataset-run.pilot-mode=false",
        "emonlens.dataset-run.poll-interval-ms=100",
        "emonlens.dataset-run.max-active-runs=2",
        "emonlens.dataset-run.worker-threads=2",
        "emonlens.eval.auto-run-enabled=false"})
@AutoConfigureMockMvc
class DatasetRunConcurrencyTest extends AbstractIntegrationTest {

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
    void allowsConfiguredConcurrentRunsAndRejectsBeyondCapacity() throws Exception {
        String datasetId = createDataset("concurrency-" + System.nanoTime());
        addItem(datasetId, "q-concurrency", "ok", null);
        String evaluatorId = createEvaluator("concurrency-judge", Map.of(
                "rubric", "回答必须正确", "minScore", 6, "referenceRequired", false));

        JsonNode first = runAsync(datasetId, evaluatorId, "run-1", 202);
        String firstId = first.path("data").path("runId").asText();
        await(() -> readRun(datasetId, firstId),
                json -> "RUNNING".equals(json.path("data").path("run").path("status").asText()),
                20000);

        JsonNode second = runAsync(datasetId, evaluatorId, "run-2", 202);
        String secondId = second.path("data").path("runId").asText();
        assertThat(secondId).isNotEqualTo(firstId);

        MockHttpServletResponse rejected = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", "run-3")
                        .contentType("application/json")
                        .content("{\"evaluatorId\":\"" + evaluatorId + "\",\"mode\":\"REPLAY\"}"))
                .andReturn().getResponse();
        assertThat(rejected.getStatus()).isEqualTo(429);

        blockingLlm.release.countDown();
        JsonNode firstDone = awaitRun(datasetId, firstId);
        JsonNode secondDone = awaitRun(datasetId, secondId);
        assertThat(firstDone.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(secondDone.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");

        JsonNode third = runAsync(datasetId, evaluatorId, "run-4", 202);
        JsonNode thirdDone = awaitRun(datasetId, third.path("data").path("runId").asText());
        assertThat(thirdDone.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
    }

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private void addItem(String datasetId, String input, String expectedOutput, String traceId)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (input != null) {
            body.put("input", input);
        }
        if (expectedOutput != null) {
            body.put("expectedOutput", expectedOutput);
        }
        if (traceId != null) {
            body.put("traceId", traceId);
        }
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
