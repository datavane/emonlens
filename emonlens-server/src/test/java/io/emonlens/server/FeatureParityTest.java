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
 * 功能对齐包端到端：Prompt 版本化/标签解析 → 模型价格 → Tag 打标/筛选 → 评论 →
 * Dataset 建集/加样本/跑实验。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-parity;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class FeatureParityTest extends AbstractIntegrationTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class FakeLlmConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        io.emonlens.server.engine.eval.LlmClient fakeLlmClient() {
            return (systemPrompt, userPrompt, model, temperature) -> "ECHO:" + userPrompt;
        }
    }

    @Test
    void promptVersioningAndLabelResolve() throws Exception {
        // v1 + 设为 production
        Map<String, Object> v1 = new HashMap<>();
        v1.put("name", "parity-prompt");
        v1.put("prompt", "v1 内容 {{question}}");
        v1.put("config", Map.of("temperature", 0.2));
        v1.put("setProduction", true);
        assertThat(readTree(postApi("/api/v1/prompts", v1)).path("code").asInt()).isZero();

        // v2（不设 production）
        Map<String, Object> v2 = new HashMap<>();
        v2.put("name", "parity-prompt");
        v2.put("prompt", "v2 内容 {{question}}");
        postApi("/api/v1/prompts", v2);

        JsonNode versions = readTree(get("/api/v1/prompts/parity-prompt/versions"));
        assertThat(versions.path("data").size()).isEqualTo(2);
        assertThat(versions.path("data").get(0).path("version").asInt()).isEqualTo(2);

        // production 解析到 v1
        JsonNode resolved = readTree(get("/api/v1/prompts/parity-prompt/resolve?label=production"));
        assertThat(resolved.path("data").path("version").asInt()).isEqualTo(1);

        // 把 production 指到 v2（发布）；再回滚到 v1
        postApi("/api/v1/prompts/parity-prompt/label", Map.of("label", "production", "version", 2));
        assertThat(readTree(get("/api/v1/prompts/parity-prompt/resolve?label=production"))
                .path("data").path("version").asInt()).isEqualTo(2);
        postApi("/api/v1/prompts/parity-prompt/label", Map.of("label", "production", "version", 1));
        assertThat(readTree(get("/api/v1/prompts/parity-prompt/resolve?label=production"))
                .path("data").path("version").asInt()).isEqualTo(1);

        // latest 恒为最高版本
        assertThat(readTree(get("/api/v1/prompts/parity-prompt/resolve?label=latest"))
                .path("data").path("version").asInt()).isEqualTo(2);
    }

    @Test
    void tagCommentAndTagFilteredTraceSearch() throws Exception {
        // ingest 一条 trace
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span("parity-agent", "parity-trace-1", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        post("/api/v1/ingest/events", spans);

        // 打标 + 枚举 + 按 tag 筛选
        postApi("/api/v1/traces/parity-trace-1/tags", Map.of("tags", List.of("regression", "beta")));
        JsonNode tags = readTree(get("/api/v1/traces/parity-trace-1/tags"));
        assertThat(tags.path("data").toString()).contains("regression").contains("beta");
        assertThat(readTree(get("/api/v1/tags")).path("data").toString()).contains("regression");

        String body = postApi("/api/v1/traces/search", Map.of("tags", List.of("regression"), "pageNumber", 1, "pageSize", 10));
        assertThat(readTree(body).path("data").path("records").toString()).contains("parity-trace-1");

        // 评论
        postApi("/api/v1/traces/parity-trace-1/comments", Map.of("content", "needs-human-review"));
        JsonNode comments = readTree(get("/api/v1/traces/parity-trace-1/comments"));
        assertThat(comments.path("data").size()).isGreaterThanOrEqualTo(1);
        assertThat(comments.path("data").get(0).path("content").asText()).isEqualTo("needs-human-review");
    }

    @Test
    void datasetCreateAddItemsAndRunExperiment() throws Exception {
        // 准备：ingest 两条 trace（一条无错、一条 LLM 错误）+ EXPRESSION 评估器
        List<Map<String, Object>> ok = new ArrayList<>();
        ok.add(span("ds-agent", "ds-trace-ok", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        post("/api/v1/ingest/events", ok);
        List<Map<String, Object>> bad = new ArrayList<>();
        bad.add(span("ds-agent", "ds-trace-bad", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        bad.add(span("ds-agent", "ds-trace-bad", "llm-root", "root", "llm", "call_model", null, "m", 1, 5));
        bad.get(1).put("status", "ERROR");
        post("/api/v1/ingest/events", bad);

        Map<String, Object> evaluator = new HashMap<>();
        evaluator.put("evaluatorKey", "ds-no-error");
        evaluator.put("type", "EXPRESSION");
        evaluator.put("spec", Map.of("type", "EXPRESSION", "passWhen", "error_count == 0"));
        String evaluatorId = readTree(postApi("/api/v1/evaluators", evaluator)).path("data").path("id").asText();

        // 建数据集 + 加样本
        String datasetId = readTree(postApi("/api/v1/datasets",
                Map.of("datasetKey", "parity-ds", "description", "测试集")))
                .path("data").path("id").asText();
        postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("traceId", "ds-trace-ok", "input", "q1", "expectedOutput", "a1"));
        postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("traceId", "ds-trace-bad", "input", "q2"));

        // 跑实验：1 PASS / 1 FAIL
        JsonNode run = readTree(postApi("/api/v1/datasets/" + datasetId + "/runs",
                Map.of("evaluatorId", evaluatorId)));
        assertThat(run.path("data").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("data").path("itemCount").asInt()).isEqualTo(2);
        assertThat(run.path("data").path("passCount").asInt()).isEqualTo(1);
        assertThat(run.path("data").path("failCount").asInt()).isEqualTo(1);

        // run 详情含逐条结果
        String runId = run.path("data").path("id").asText();
        JsonNode runDetail = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId));
        assertThat(runDetail.path("data").path("items").size()).isEqualTo(2);
    }

    @org.junit.jupiter.api.Test
    void ingestDuplicateSpanIsIdempotent() throws Exception {
        // 同一批内含重复 (traceId, spanId)：重复跳过，其余正常落库（生产幂等）
        java.util.List<Map<String, Object>> spans = new java.util.ArrayList<>();
        spans.add(span("dup-agent", "dup-trace-1", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        spans.add(span("dup-agent", "dup-trace-1", "tool-1", "root", "tool", "t1", "t1", null, 2, 3));
        spans.add(span("dup-agent", "dup-trace-1", "tool-1", "root", "tool", "t1", "t1", null, 2, 3)); // 重复
        spans.add(span("dup-agent", "dup-trace-1", "tool-2", "root", "tool", "t2", "t2", null, 3, 4));
        post("/api/v1/ingest/events", spans);

        String detail = await(
                () -> {
                    try {
                        return get("/api/v1/traces/dup-trace-1");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    try {
                        return readTree(json).path("data").path("spans").size() >= 3;
                    } catch (Exception e) {
                        return false;
                    }
                },
                ASYNC_TIMEOUT_MS);
        assertThat(readTree(detail).path("data").path("spans").size()).isEqualTo(3);
    }

    @org.junit.jupiter.api.Test
    void ingestBatchSizeCap() throws Exception {
        // 501 条 → 整批拒绝（400 业务码）
        java.util.List<Map<String, Object>> tooBig = new java.util.ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooBig.add(span("cap-agent", "cap-trace", "s" + i, null, "agent", "x", null, null, 1, 2));
        }
        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/ingest/events")
                        .header("Authorization", adminAuth())
                        .header("X-Trust-Key", INGEST_KEY)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tooBig)))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).containsAnyOf("batch too large", "40001");
    }

    @Test
    void traceContentSearchAndSorting() throws Exception {
        List<Map<String, Object>> a = new ArrayList<>();
        Map<String, Object> la = span("q-agent", "q-trace-alpha", "llm-a", "root-a", "llm", "call_model", null, "m", 1, 3);
        Map<String, Object> ra = span("q-agent", "q-trace-alpha", "root-a", null, "agent", "invoke_agent", null, null, 1, 5);
        la.put("input", "MAGICZEBRA 请查账单");
        a.add(ra); a.add(la);
        post("/api/v1/ingest/events", a);
        // 第二条：更长耗时（9s），排序断言用
        List<Map<String, Object>> b = new ArrayList<>();
        b.add(span("q-agent", "q-trace-beta", "root-b", null, "agent", "invoke_agent", null, null, 1, 9));
        post("/api/v1/ingest/events", b);

        String hit = postApi("/api/v1/traces/search", Map.of("query", "MAGICZEBRA", "pageNumber", 1, "pageSize", 50));
        assertThat(readTree(hit).path("data").path("records").toString()).contains("q-trace-alpha");
        String miss = postApi("/api/v1/traces/search", Map.of("query", "NOSUCHTEXT", "pageNumber", 1, "pageSize", 50));
        assertThat(readTree(miss).path("data").path("records").toString()).doesNotContain("q-trace-alpha");

        String sorted = postApi("/api/v1/traces/search",
                Map.of("agentKeys", List.of("q-agent"), "sortBy", "durationMs", "sortOrder", "asc", "pageNumber", 1, "pageSize", 50));
        JsonNode records = readTree(sorted).path("data").path("records");
        assertThat(records.size()).isGreaterThanOrEqualTo(2);
        assertThat(records.get(0).path("durationMs").asLong())
                .isLessThanOrEqualTo(records.get(records.size() - 1).path("durationMs").asLong());
    }

    @Test
    void environmentIngestFilterAndAnnotationQueue() throws Exception {
        List<Map<String, Object>> spans = new ArrayList<>();
        Map<String, Object> root = span("env-agent", "env-trace-1", "root", null, "agent", "invoke_agent", null, null, 1, 5);
        root.put("environment", "staging");
        spans.add(root);
        post("/api/v1/ingest/events", spans);

        String body = postApi("/api/v1/traces/search",
                Map.of("environments", List.of("staging"), "pageNumber", 1, "pageSize", 50));
        assertThat(readTree(body).path("data").path("records").toString()).contains("env-trace-1");
        assertThat(readTree(get("/api/v1/environments")).path("data").toString()).contains("staging");

        String queueId = readTree(postApi("/api/v1/annotation-queues",
                Map.of("name", "parity-queue", "description", "d")))
                .path("data").path("id").asText();
        Integer added = readTree(postApi("/api/v1/annotation-queues/" + queueId + "/items",
                Map.of("traceIds", List.of("env-trace-1")))).path("data").asInt();
        assertThat(added).isEqualTo(1);

        JsonNode next = readTree(get("/api/v1/annotation-queues/" + queueId + "/next")).path("data");
        assertThat(next.path("status").asText()).isEqualTo("PENDING");
        String itemId = next.path("id").asText();

        JsonNode done = readTree(postApi("/api/v1/annotation-queues/" + queueId + "/items/" + itemId + "/complete",
                Map.of("score", 9, "comment", "looks good"))).path("data");
        assertThat(done.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.path("reviewer").asText()).isEqualTo("admin");

        JsonNode scores = readTree(get("/api/v1/traces/env-trace-1/scores"));
        assertThat(scores.path("data").path("records").size()).isGreaterThanOrEqualTo(1);

        // 删除队列必须级联清理标注写入的人工评分：否则残留 evaluator_key 指向不存在队列的孤儿行
        deleteApi("/api/v1/annotation-queues/" + queueId);
        JsonNode afterDelete = readTree(get("/api/v1/traces/env-trace-1/scores"));
        for (JsonNode r : afterDelete.path("data").path("records")) {
            assertThat(r.path("evaluatorKey").asText()).doesNotContain("annotation:" + queueId);
        }
        JsonNode detail = readTree(get("/api/v1/annotation-queues/" + queueId));
        assertThat(detail.path("code").asInt()).isNotZero();   // 队列已不存在
    }

    @Test
    void playgroundRendersVariablesAndCallsLlm() throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("systemPrompt", "你是客服助手");
        request.put("prompt", "你好 {{name}}，资料 {{context}}");
        request.put("variables", Map.of("name", "王小明", "context", "cp-001"));
        request.put("model", "gpt-4o-mini");
        request.put("temperature", 0.2);

        JsonNode result = readTree(postApi("/api/v1/playground/run", request)).path("data");
        assertThat(result.path("status").asText()).isEqualTo("OK");
        // 变量已渲染进 user prompt（Fake LLM 回显 user prompt）
        // MockMvc 响应默认 ISO-8859-1，中文断言不可靠；用 ASCII 片段验证变量已渲染
        assertThat(result.path("output").asText()).startsWith("ECHO:").contains("cp-001");
        assertThat(result.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(result.path("latencyMs").asLong()).isGreaterThanOrEqualTo(0);

        // 空提示词 → 结构化 ERROR 而非 500
        JsonNode empty = readTree(postApi("/api/v1/playground/run", Map.of("prompt", ""))).path("data");
        assertThat(empty.path("status").asText()).isEqualTo("ERROR");

        // 超长提示词 / 超量变量 → 结构化 ERROR（防失控 LLM 成本），不触发 LLM 调用
        JsonNode oversized = readTree(postApi("/api/v1/playground/run",
                Map.of("prompt", "x".repeat(200_001)))).path("data");
        assertThat(oversized.path("status").asText()).isEqualTo("ERROR");
        assertThat(oversized.path("message").asText()).contains("过长");
        JsonNode tooManyVars = readTree(postApi("/api/v1/playground/run",
                Map.of("prompt", "hi",
                        "variables", java.util.stream.IntStream.range(0, 201)
                                .boxed()
                                .collect(java.util.stream.Collectors.toMap(i -> "k" + i, i -> "v"))))).path("data");
        assertThat(tooManyVars.path("status").asText()).isEqualTo("ERROR");
        assertThat(tooManyVars.path("message").asText()).contains("变量");
    }

    @Test
    void modelPriceCrudAndCostFallback() throws Exception {
        // 价格 CRUD
        String priceId = readTree(postApi("/api/v1/model-prices",
                Map.of("model", "priced-model", "inputPerMillion", 1.0, "outputPerMillion", 2.0)))
                .path("data").path("id").asText();
        assertThat(readTree(putApi("/api/v1/model-prices/" + priceId,
                Map.of("inputPerMillion", 1.5))).path("code").asInt()).isZero();

        // ingest 使用 priced-model 的 trace：512 in / 128 out → 1.5*512/1M + 2*128/1M ≈ 0.001024
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span("price-agent", "price-trace-1", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        Map<String, Object> llm = span("price-agent", "price-trace-1", "llm-root", "root", "llm", "call_model", null, "priced-model", 1, 5);
        llm.put("inputTokens", 512);
        llm.put("outputTokens", 128);
        spans.add(llm);
        post("/api/v1/ingest/events", spans);

        // ingest 异步落库，轮询等待 summary 出现估算成本
        String detail = await(
                () -> {
                    try {
                        return get("/api/v1/traces/price-trace-1");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> { try { return readTree(json).path("data").path("summary").path("totalCostUsd").asDouble() > 0; } catch (Exception e) { return false; } },
                ASYNC_TIMEOUT_MS);
        JsonNode cost = readTree(detail).path("data").path("summary").path("totalCostUsd");
        assertThat(cost.doubleValue()).isGreaterThan(0).isLessThan(0.01);
    }
}
