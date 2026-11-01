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

/**
 * 合成数据集：异步候选生成、critic 过滤、去重、人工批准入 ACTIVE、幂等。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-synthesis;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.dataset-synthesis.enabled=true",
        "emonlens.dataset-synthesis.poll-interval-ms=200",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class DatasetSynthesisFlowTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    static class FakeLlm implements LlmClient {
        final AtomicInteger generation = new AtomicInteger();
        final List<String[]> calls = new CopyOnWriteArrayList<>();
        volatile double criticScore = 0.9;
        volatile boolean fixedInput = false;
        volatile String fixed = "{\"input\":\"same\",\"expected_output\":\"same\"}";

        @Override
        public String chat(String systemPrompt, String userPrompt, String model, double temperature) {
            return chatWithUsage(systemPrompt, userPrompt, model, temperature).content();
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt,
                                       String model, double temperature) {
            calls.add(new String[] {systemPrompt, userPrompt});
            if (systemPrompt != null && systemPrompt.contains("dataset synthesis generator")) {
                String json = fixedInput ? fixed
                        : "{\"input\":\"gen-" + generation.incrementAndGet()
                        + "\",\"expected_output\":\"ans-" + generation.get() + "\"}";
                return new ChatReply(json, 5, 7);
            }
            if (systemPrompt != null && systemPrompt.contains("dataset synthesis critic")) {
                return new ChatReply("{\"score\":" + criticScore + ",\"reason\":\"ok\"}", 3, 2);
            }
            return new ChatReply("ok", 1, 1);
        }
    }

    @javax.annotation.Resource
    private FakeLlm fakeLlm;

    @BeforeEach
    void reset() {
        fakeLlm.generation.set(0);
        fakeLlm.calls.clear();
        fakeLlm.criticScore = 0.9;
        fakeLlm.fixedInput = false;
    }

    @Test
    void generatesCandidatesAndRequiresHumanApproval() throws Exception {
        String datasetId = createDataset("synthesis-main");
        String jobId = createJob(datasetId, "syn-key-1", scratchSource(3),
                Map.of("maxCandidates", 3, "minQuality", 0.6, "maxRetries", 0,
                        "evolutions", Map.of("REASONING", 1.0)));
        JsonNode job = awaitJob(datasetId, jobId);
        assertThat(job.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.path("totalCandidates").asInt()).isEqualTo(3);

        JsonNode candidates = readTree(get("/api/v1/datasets/" + datasetId
                + "/synthesis-jobs/" + jobId + "/candidates"));
        assertThat(candidates.path("data")).hasSize(3);
        String firstId = candidates.path("data").get(0).path("id").asText();
        String secondId = candidates.path("data").get(1).path("id").asText();
        assertThat(candidates.path("data").get(0).path("status").asText()).isEqualTo("DRAFT");

        JsonNode approved = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/synthesis-jobs/" + jobId + "/candidates/" + firstId + "/approve", Map.of()));
        assertThat(approved.path("code").asInt()).isZero();
        assertThat(approved.path("data").path("sourceType").asText()).isEqualTo("SYNTHETIC");
        assertThat(approved.path("data").path("sourceRef").asText()).startsWith("synthesis:");

        JsonNode rejected = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/synthesis-jobs/" + jobId + "/candidates/" + secondId + "/reject",
                Map.of("note", "not realistic")));
        assertThat(rejected.path("code").asInt()).isZero();
        assertThat(rejected.path("data").path("status").asText()).isEqualTo("REJECTED");

        JsonNode detail = readTree(get("/api/v1/datasets/" + datasetId));
        assertThat(detail.path("data").path("items")).hasSize(1);
    }

    @Test
    void lowQualityCandidatesAreRejectedAndCannotBeApproved() throws Exception {
        fakeLlm.criticScore = 0.2;
        String datasetId = createDataset("synthesis-low");
        String jobId = createJob(datasetId, "syn-key-low", scratchSource(2),
                Map.of("maxCandidates", 2, "minQuality", 0.8, "maxRetries", 0,
                        "evolutions", Map.of("REASONING", 1.0)));
        JsonNode job = awaitJob(datasetId, jobId);
        assertThat(job.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.path("rejectedCount").asInt()).isEqualTo(2);

        JsonNode candidates = readTree(get("/api/v1/datasets/" + datasetId
                + "/synthesis-jobs/" + jobId + "/candidates"));
        assertThat(candidates.path("data")).hasSize(2);
        for (JsonNode candidate : candidates.path("data")) {
            assertThat(candidate.path("status").asText()).isEqualTo("REJECTED");
            assertThat(candidate.path("filterReason").asText()).contains("LOW_QUALITY");
        }
        String id = candidates.path("data").get(0).path("id").asText();
        JsonNode approve = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/synthesis-jobs/" + jobId + "/candidates/" + id + "/approve", Map.of()));
        assertThat(approve.path("code").asInt()).isEqualTo(40901);
    }

    @Test
    void duplicateCandidatesAreFilteredWithinJob() throws Exception {
        fakeLlm.fixedInput = true;
        String datasetId = createDataset("synthesis-dup");
        String jobId = createJob(datasetId, "syn-key-dup", scratchSource(3),
                Map.of("maxCandidates", 3, "minQuality", 0.6, "maxRetries", 0,
                        "evolutions", Map.of("REASONING", 1.0)));
        JsonNode job = awaitJob(datasetId, jobId);
        assertThat(job.path("totalCandidates").asInt()).isEqualTo(3);
        JsonNode candidates = readTree(get("/api/v1/datasets/" + datasetId
                + "/synthesis-jobs/" + jobId + "/candidates"));
        long drafts = 0;
        long duplicates = 0;
        for (JsonNode candidate : candidates.path("data")) {
            if ("DRAFT".equals(candidate.path("status").asText())) {
                drafts++;
            }
            if ("DUPLICATE".equals(candidate.path("filterReason").asText())) {
                duplicates++;
            }
        }
        assertThat(drafts).isEqualTo(1);
        assertThat(duplicates).isEqualTo(2);
    }

    @Test
    void sameIdempotencyKeyReturnsSameJob() throws Exception {
        String datasetId = createDataset("synthesis-idem");
        JsonNode first = createJobRaw(datasetId, "syn-key-idem", scratchSource(1),
                Map.of("maxCandidates", 1, "minQuality", 0.6, "maxRetries", 0,
                        "evolutions", Map.of("REASONING", 1.0)));
        JsonNode second = createJobRaw(datasetId, "syn-key-idem", scratchSource(1),
                Map.of("maxCandidates", 1, "minQuality", 0.6, "maxRetries", 0,
                        "evolutions", Map.of("REASONING", 1.0)));
        assertThat(first.path("data").path("created").asBoolean()).isTrue();
        assertThat(second.path("data").path("created").asBoolean()).isFalse();
        assertThat(second.path("data").path("job").path("id").asText())
                .isEqualTo(first.path("data").path("job").path("id").asText());
        awaitJob(datasetId, first.path("data").path("job").path("id").asText());
    }

    @Test
    void sameIdempotencyKeyOnAnotherDatasetConflicts() throws Exception {
        String firstDataset = createDataset("synthesis-idem-a");
        String secondDataset = createDataset("synthesis-idem-b");
        Map<String, Object> config = Map.of("maxCandidates", 1, "minQuality", 0.6,
                "maxRetries", 0, "evolutions", Map.of("REASONING", 1.0));
        createJobRaw(firstDataset, "syn-key-cross-dataset", scratchSource(1), config);

        Map<String, Object> body = new HashMap<>();
        body.put("sourceType", "SCRATCH");
        body.put("source", scratchSource(1));
        body.put("config", config);
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + secondDataset + "/synthesis-jobs")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", "syn-key-cross-dataset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
        // 业务错误走 HTTP 200 + ApiResult.code；CONFLICT=40901
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode conflict = readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(conflict.path("code").asInt()).isEqualTo(40901);
    }

    // ---------- helpers ----------

    private Map<String, Object> scratchSource(int numGoldens) {
        return Map.of("numGoldens", numGoldens);
    }

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private String createJob(String datasetId, String key, Map<String, Object> source,
                             Map<String, Object> config) throws Exception {
        return createJobRaw(datasetId, key, source, config)
                .path("data").path("job").path("id").asText();
    }

    private JsonNode createJobRaw(String datasetId, String key, Map<String, Object> source,
                                  Map<String, Object> config) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("sourceType", "SCRATCH");
        body.put("source", source);
        body.put("config", config);
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/synthesis-jobs")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode json = readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertThat(json.path("code").asInt()).as(json.toString()).isZero();
        return json;
    }

    private JsonNode awaitJob(String datasetId, String jobId) throws Exception {
        return await(
                () -> {
                    try {
                        return readTree(get("/api/v1/datasets/" + datasetId
                                + "/synthesis-jobs/" + jobId)).path("data");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    String status = json.path("status").asText();
                    return !"PENDING".equals(status) && !"RUNNING".equals(status);
                },
                ASYNC_TIMEOUT_MS);
    }
}
