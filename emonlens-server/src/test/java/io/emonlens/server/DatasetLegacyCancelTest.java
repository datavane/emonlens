package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;


import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.engine.eval.LlmClient;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.mapper.DatasetRunMapper;

/**
 * 旧同步入口与取消接口的竞态：同步执行结束时的 conditional update 不能把 CANCELLED 覆盖回 COMPLETED。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-legacy-cancel;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.dataset-run.pilot-mode=false",
        "emonlens.dataset-run.poll-interval-ms=200",
        "emonlens.eval.auto-run-enabled=false"})
@AutoConfigureMockMvc
class DatasetLegacyCancelTest extends AbstractIntegrationTest {

    @Resource
    private FakeLlm fakeLlm;
    @Resource
    private DatasetRunMapper datasetRunMapper;

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    static class FakeLlm implements LlmClient {
        final CountDownLatch block = new CountDownLatch(1);
        volatile boolean blocking = true;

        @Override
        public String chat(String systemPrompt, String userPrompt, String model, double temperature) {
            return chatWithUsage(systemPrompt, userPrompt, model, temperature).content();
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt, String model, double temperature) {
            if (blocking) {
                try {
                    block.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ChatReply("A", 1, 1);
        }
    }

    @Test
    void legacySyncCompletionCannotOverwriteCancelledRun() throws Exception {
        String datasetId = readTree(postApi("/api/v1/datasets",
                Map.of("datasetKey", "legacy-cancel-" + System.nanoTime())))
                .path("data").path("id").asText();
        postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("input", "q-legacy-cancel", "expectedOutput", "A"));

        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "EXPRESSION");
        spec.put("passWhen", "reference_match.exact(candidate_output, expected_output)");
        spec.put("referenceRequired", true);
        String evaluatorId = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", "legacy-cancel-exact",
                "type", "EXPRESSION",
                "spec", spec))).path("data").path("id").asText();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> response = executor.submit(() -> mockMvc.perform(MockMvcRequestBuilders.post(
                            "/api/v1/datasets/" + datasetId + "/runs")
                            .header("Authorization", adminAuth())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "evaluatorId", evaluatorId,
                                    "mode", "REPLAY",
                                    "model", "gpt-4o-mini",
                                    "systemPrompt", "You are a helpful assistant."))))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

            DatasetRunEntity run = awaitRunning(datasetId);
            JsonNode cancelled = readTree(postApi(
                    "/api/v1/datasets/" + datasetId + "/runs/" + run.getId() + "/cancel", Map.of()));
            assertThat(cancelled.path("data").path("run").path("status").asText()).isEqualTo("CANCELLED");

            fakeLlm.blocking = false;
            fakeLlm.block.countDown();
            response.get(10, TimeUnit.SECONDS);

            assertThat(datasetRunMapper.selectById(run.getId()).getStatus())
                    .isEqualTo(DatasetRunEntity.STATUS_CANCELLED);
        } finally {
            fakeLlm.block.countDown();
            executor.shutdownNow();
        }
    }

    private DatasetRunEntity awaitRunning(String datasetId) throws Exception {
        for (int i = 0; i < 100; i++) {
            DatasetRunEntity run = datasetRunMapper.selectOne(Wrappers.<DatasetRunEntity>lambdaQuery()
                    .eq(DatasetRunEntity::getDatasetId, datasetId)
                    .orderByDesc(DatasetRunEntity::getStartedAt)
                    .last("LIMIT 1"));
            if (run != null && DatasetRunEntity.STATUS_RUNNING.equals(run.getStatus())) {
                return run;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("legacy run did not reach RUNNING");
    }
}
