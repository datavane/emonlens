package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 评测样本契约 v2：v1 回归、v2 字段往返、revision/snapshot 冻结、
 * TRACE 工具/检索投影、写入口校验。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-case-v2;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.dataset-run.pilot-mode=true",
        "emonlens.dataset-run.poll-interval-ms=200",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class DatasetCaseV2FlowTest extends AbstractIntegrationTest {

    @Test
    void v2FieldsSurviveRevisionSnapshotAndEvaluation() throws Exception {
        String traceId = ingestCompletedTrace("查询订单 123", "订单 123 已发货");

        String datasetId = createDataset("case-v2");
        Map<String, Object> body = new HashMap<>();
        body.put("traceId", traceId);
        body.put("input", "查询订单 123");
        body.put("expectedOutput", "已发货");
        body.put("schemaVersion", 2);
        body.put("retrievalContext", List.of(Map.of(
                "id", "doc-1",
                "content", "订单 123 已发货",
                "score", 0.9,
                "source", "kb/order.md")));
        body.put("expectedTools", List.of(Map.of(
                "name", "query_order",
                "type", "FUNCTION",
                "inputParameters", Map.of("orderId", "123"),
                "required", true)));
        body.put("turns", List.of(
                Map.of("role", "user", "content", "订单 123 到哪了？"),
                Map.of("role", "assistant", "content", "已发货")));
        body.put("expectations", Map.of(
                "must", List.of("引用订单状态"),
                "mustNot", List.of("承诺退款"),
                "unknownPolicy", "BLOCK"));
        JsonNode added = readTree(postApi("/api/v1/datasets/" + datasetId + "/items", body));
        assertThat(added.path("code").asInt()).isZero();
        assertThat(added.path("data").path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(added.path("data").path("contentHash").asText()).hasSize(64);

        String evaluatorId = createEvaluator("case-v2-shape", "EXPRESSION", Map.of(
                "passWhen", "retrieval_context.size() > 0 and tools_called.size() > 0"
                        + " and expected_tools.size() > 0 and turns.size() > 0"
                        + " and expectations['must'].size() > 0"));

        JsonNode run = awaitRun(datasetId, runTrace(datasetId, evaluatorId, "case-v2-run-1"));
        assertThat(run.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("data").path("run").path("passCount").asInt()).isEqualTo(1);
        assertThat(run.path("data").path("run").path("failCount").asInt()).isZero();

        JsonNode item = run.path("data").path("items").get(0);
        JsonNode snapshot = item.path("sampleSnapshot");
        assertThat(snapshot.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(snapshot.path("caseHash").asText()).hasSize(64);
        assertThat(snapshot.path("retrievalContextSource").asText()).isEqualTo("SAMPLE");
        assertThat(snapshot.path("retrievalContext").get(0).path("content").asText())
                .isEqualTo("订单 123 已发货");
        assertThat(snapshot.path("toolsCalled").get(0).path("name").asText())
                .isEqualTo("query_order");
        assertThat(snapshot.path("toolsCalled").get(0).path("inputParameters").path("orderId").asText())
                .isEqualTo("123");
        assertThat(snapshot.path("expectedTools").get(0).path("required").asBoolean()).isTrue();
        assertThat(snapshot.path("turns")).hasSize(2);
        assertThat(snapshot.path("expectations").path("must").get(0).asText())
                .isEqualTo("引用订单状态");
    }

    @Test
    void traceProjectsToolsAndRetrievalWhenSampleDoesNotProvideThem() throws Exception {
        String traceId = ingestCompletedTrace("查询订单 456", "订单 456 已发货");

        String datasetId = createDataset("case-v2-projection");
        Map<String, Object> body = new HashMap<>();
        body.put("traceId", traceId);
        body.put("input", "查询订单 456");
        body.put("schemaVersion", 2);
        body.put("turns", List.of(Map.of("role", "user", "content", "订单 456 到哪了？")));
        JsonNode added = readTree(postApi("/api/v1/datasets/" + datasetId + "/items", body));
        assertThat(added.path("code").asInt()).isZero();

        String evaluatorId = createEvaluator("case-v2-projection-shape", "EXPRESSION", Map.of(
                "passWhen", "tools_called.size() > 0 and retrieval_context.size() > 0"));

        JsonNode run = awaitRun(datasetId, runTrace(datasetId, evaluatorId, "case-v2-run-2"));
        JsonNode snapshot = run.path("data").path("items").get(0).path("sampleSnapshot");
        assertThat(snapshot.path("retrievalContextSource").asText()).isEqualTo("TRACE");
        assertThat(snapshot.path("toolsCalled").get(0).path("name").asText())
                .isEqualTo("query_order");
        assertThat(snapshot.path("retrievalContext").get(0).path("content").asText())
                .isEqualTo("订单 456 已发货");
    }

    @Test
    void v1ItemsRemainUnchangedAndInvalidV2IsRejected() throws Exception {
        String datasetId = createDataset("case-v2-validation");
        JsonNode v1 = readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("input", "旧样本", "expectedOutput", "旧答案")));
        assertThat(v1.path("code").asInt()).isZero();
        assertThat(v1.path("data").path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(v1.path("data").path("retrievalContext").isMissingNode()
                || v1.path("data").path("retrievalContext").isNull()).isTrue();

        JsonNode explicitV1 = readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("schemaVersion", 1, "input", "旧样本",
                        "retrievalContext", List.of(Map.of("content", "x")))));
        assertThat(explicitV1.path("code").asInt()).isEqualTo(40000);

        JsonNode badRole = readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("schemaVersion", 2, "input", "x",
                        "turns", List.of(Map.of("role", "robot", "content", "x")))));
        assertThat(badRole.path("code").asInt()).isEqualTo(40000);
        assertThat(badRole.path("message").asText()).contains("role");

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(Map.of("content", "ctx-" + i));
        }
        JsonNode tooManyContexts = readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("schemaVersion", 2, "input", "x", "retrievalContext", tooMany)));
        assertThat(tooManyContexts.path("code").asInt()).isEqualTo(40000);
        assertThat(tooManyContexts.path("message").asText()).contains("最多 20 条");
    }

    // ---------- helpers ----------

    private String ingestCompletedTrace(String input, String output) throws Exception {
        // 用唯一 trace/span id，避免同一 JVM 内多测试方法共用固定 id 造成互相干扰
        String suffix = Long.toString(System.nanoTime(), 36);
        String traceId = "case-v2-" + suffix;
        String rootId = "root-" + suffix;
        List<Map<String, Object>> spans = new ArrayList<>();
        Map<String, Object> root = span("case-agent", traceId, rootId, null,
                "agent", "invoke_agent", null, null, 1, 10);
        root.put("input", input);
        root.put("output", output);

        Map<String, Object> tool = span("case-agent", traceId, "tool-" + suffix, rootId,
                "tool", "query_order", "query_order", null, 2, 3);
        tool.put("input", "{\"orderId\":\"123\"}");
        tool.put("output", "{\"status\":\"SHIPPED\"}");

        Map<String, Object> retrieval = span("case-agent", traceId, "retrieval-" + suffix, rootId,
                "retrieval", "search_kb", "kb_search", null, 4, 5);
        retrieval.put("output", output);

        spans.add(root);
        spans.add(tool);
        spans.add(retrieval);
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
        return traceId;
    }

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private String createEvaluator(String key, String type, Map<String, Object> spec) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/evaluators",
                Map.of("evaluatorKey", key, "type", type, "spec", spec)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private String runTrace(String datasetId, String evaluatorId, String idempotencyKey)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("evaluatorId", evaluatorId);
        body.put("mode", "TRACE");
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", idempotencyKey)
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
