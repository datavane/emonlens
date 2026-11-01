package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * Judge 模板版本化 + claim 判分：平台模板、workspace 模板、逐条 claim 落库、
 * requiredFields 缺证据返回 UNKNOWN。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-claim-judge;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.dataset-run.pilot-mode=true",
        "emonlens.dataset-run.poll-interval-ms=200",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class ClaimJudgeFlowTest extends AbstractIntegrationTest {

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
        volatile String claimOutput =
                "{\"claims\":[{\"id\":\"c1\",\"text\":\"答案是 42\",\"verdict\":\"PASS\","
                        + "\"evidenceRefs\":[\"completion\"],\"reason\":\"输出明确给出 42\"}],"
                        + "\"reason\":\"ok\"}";

        @Override
        public String chat(String systemPrompt, String userPrompt, String model, double temperature) {
            return chatWithUsage(systemPrompt, userPrompt, model, temperature).content();
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt,
                                       String model, double temperature) {
            calls.add(new String[] { systemPrompt, userPrompt });
            if (systemPrompt != null && systemPrompt.contains("strict evidence-based evaluator")) {
                return new ChatReply(claimOutput, 11, 22);
            }
            return new ChatReply("答案是 42", 7, 13);
        }
    }

    @javax.annotation.Resource
    private FakeLlm fakeLlm;

    @Test
    void claimJudgePersistsClaimsAndTemplateFingerprint() throws Exception {
        fakeLlm.claimOutput = "{\"claims\":["
                + "{\"id\":\"c1\",\"text\":\"答案是 42\",\"verdict\":\"PASS\","
                + "\"evidenceRefs\":[\"completion\"],\"reason\":\"输出明确给出 42\"},"
                + "{\"id\":\"c2\",\"text\":\"答案有依据\",\"verdict\":\"UNKNOWN\","
                + "\"evidenceRefs\":[],\"reason\":\"没有检索证据\"}],\"reason\":\"部分可确认\"}";
        String datasetId = createDataset("claim-judge");
        addItem(datasetId, "q-claim", "42");
        String evaluatorId = createEvaluator("claim-judge", claimSpec("platform/claim-v1",
                Map.of("maxClaims", 5, "requireEvidence", true,
                        "unknownPolicy", "PASS", "minPassRatio", 0.8)));
        String runId = runReplay(datasetId, evaluatorId, "claim-judge-run-1");
        JsonNode detail = awaitRun(datasetId, runId);

        assertThat(detail.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("data").path("run").path("passCount").asInt()).isEqualTo(1);
        JsonNode result = detail.path("data").path("items").get(0)
                .path("evaluatorResults").get(0);
        assertThat(result.path("judgeMode").asText()).isEqualTo("CLAIMS");
        assertThat(result.path("judgeTemplate").path("key").asText())
                .isEqualTo("platform/claim-v1");
        assertThat(result.path("judgeTemplate").path("hash").asText()).hasSize(64);
        assertThat(result.path("claims")).hasSize(2);
        assertThat(result.path("claims").get(0).path("verdict").asText()).isEqualTo("PASS");
        assertThat(result.path("claimAggregate").path("unknownCount").asInt()).isEqualTo(1);

        JsonNode tasks = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId + "/tasks"));
        JsonNode task = tasks.path("data").path("records").get(0);
        assertThat(task.path("judgeTemplateKey").asText()).isEqualTo("platform/claim-v1");
        assertThat(task.path("claims")).hasSize(2);
        assertThat(task.path("claimAggregate").path("passCount").asInt()).isEqualTo(1);
    }

    @Test
    void claimJudgeWithBlockPolicyReturnsUnknown() throws Exception {
        fakeLlm.claimOutput = "{\"claims\":[{\"id\":\"c1\",\"text\":\"答案有依据\","
                + "\"verdict\":\"UNKNOWN\",\"evidenceRefs\":[],\"reason\":\"没有检索证据\"}],"
                + "\"reason\":\"无法确认\"}";
        String datasetId = createDataset("claim-judge-block");
        addItem(datasetId, "q-block", "42");
        String evaluatorId = createEvaluator("claim-judge-block", claimSpec("platform/claim-v1",
                Map.of("maxClaims", 5, "requireEvidence", true,
                        "unknownPolicy", "BLOCK", "minPassRatio", 0.8)));
        String runId = runReplay(datasetId, evaluatorId, "claim-judge-run-block");
        JsonNode detail = awaitRun(datasetId, runId);

        JsonNode item = detail.path("data").path("items").get(0);
        assertThat(item.path("label").asText()).isEqualTo("UNKNOWN");
        assertThat(item.path("evaluatorResults").get(0).path("reasonCode").asText())
                .isEqualTo("CLAIM_UNKNOWN");
        assertThat(detail.path("data").path("run").path("unknownCount").asInt()).isEqualTo(1);
    }

    @Test
    void evaluatorPinsTemplateVersionAcrossNewTemplateReleases() throws Exception {
        Map<String, Object> template = new HashMap<>();
        template.put("templateKey", "workspace/pinned-claim");
        template.put("name", "pinned claim v1");
        template.put("systemPrompt", "You are a strict evidence-based evaluator. Pinned v1.");
        template.put("outputSchema", "{\"claims\":\"array\"}");
        JsonNode createdV1 = readTree(postApi("/api/v1/judge-templates", template)).path("data");
        assertThat(createdV1.path("version").asInt()).isEqualTo(1);
        String v1Id = createdV1.path("id").asText();

        String datasetId = createDataset("claim-judge-pinned");
        addItem(datasetId, "q-pinned", "42");
        String evaluatorId = createEvaluator("claim-judge-pinned",
                claimSpec("workspace/pinned-claim", Map.of("maxClaims", 5)));
        JsonNode versions = readTree(get("/api/v1/evaluators/" + evaluatorId + "/versions"));
        assertThat(versions.path("data").get(0).path("templateVersion").asInt()).isEqualTo(1);

        // 发布 v2 后，已有评估器仍应固定 v1，不能让“最新模板”改变运行语义
        template.put("name", "pinned claim v2");
        template.put("systemPrompt", "You are a strict evidence-based evaluator. Pinned v2.");
        JsonNode createdV2 = readTree(postApi("/api/v1/judge-templates", template)).path("data");
        assertThat(createdV2.path("version").asInt()).isEqualTo(2);
        String v2Id = createdV2.path("id").asText();
        // 停用 v1 只阻止新评估器选它；固定到 v1 的旧评估器必须仍能重放
        assertThat(readTree(postApi("/api/v1/judge-templates/" + v1Id + "/deprecate",
                Map.of())).path("data").path("status").asText()).isEqualTo("DEPRECATED");

        String runId = runReplay(datasetId, evaluatorId, "claim-judge-run-pinned");
        JsonNode detail = awaitRun(datasetId, runId);
        assertThat(detail.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        JsonNode task = readTree(get("/api/v1/datasets/" + datasetId
                + "/runs/" + runId + "/tasks")).path("data").path("records").get(0);
        assertThat(task.path("judgeTemplateKey").asText()).isEqualTo("workspace/pinned-claim");
        assertThat(task.path("judgeTemplateVersion").asInt()).isEqualTo(1);

        // 停用最新版本后，同内容重新发布应生成新的 PUBLISHED 版本，而不是返回已停用行
        assertThat(readTree(postApi("/api/v1/judge-templates/" + v2Id + "/deprecate",
                Map.of())).path("data").path("status").asText()).isEqualTo("DEPRECATED");
        JsonNode republished = readTree(postApi("/api/v1/judge-templates", template)).path("data");
        assertThat(republished.path("version").asInt()).isEqualTo(3);
        assertThat(republished.path("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void claimTextIsRedactedWithoutContentPermission() throws Exception {
        fakeLlm.claimOutput = "{\"claims\":[{\"id\":\"c1\",\"text\":\"答案包含敏感内容\","
                + "\"verdict\":\"PASS\",\"evidenceRefs\":[\"completion\"],\"reason\":\"敏感理由\"}],"
                + "\"reason\":\"ok\"}";
        String datasetId = createDataset("claim-judge-redact");
        addItem(datasetId, "q-redact", "42");
        String evaluatorId = createEvaluator("claim-judge-redact", claimSpec("platform/claim-v1",
                Map.of("maxClaims", 5)));
        String runId = runReplay(datasetId, evaluatorId, "claim-judge-run-redact");
        awaitRun(datasetId, runId);

        JsonNode role = readTree(postApi("/api/v1/sys/roles",
                Map.of("name", "claim contentless")));
        String roleId = role.path("data").path("id").asText();
        putApi("/api/v1/sys/roles/" + roleId + "/permissions", Map.of("permissions", List.of(
                "workspace:trust:dataset:query", "workspace:trust:evaluation:query")));
        postApi("/api/v1/sys/users", Map.of(
                "username", "claim-viewer", "password", "viewer-123456",
                "name", "claim 只读", "roleId", roleId));
        String login = postApi("/api/v1/auth/login",
                Map.of("username", "claim-viewer", "password", "viewer-123456"));
        String viewerToken = "Bearer " + readTree(login).path("data").path("token").asText();

        JsonNode data = readTree(mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/datasets/" + datasetId + "/runs/" + runId)
                        .header("Authorization", viewerToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(data.path("contentRestricted").asBoolean()).isTrue();
        JsonNode result = data.path("items").get(0).path("evaluatorResults").get(0);
        assertThat(result.has("claims")).isFalse();
        assertThat(result.has("reason")).isFalse();
        assertThat(data.path("items").get(0).path("resultDetails")
                .path("evaluators").get(0).has("claims")).isFalse();
    }

    @Test
    void claimJudgeRejectsOutputAboveMaxClaims() throws Exception {
        StringBuilder claims = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            if (i > 1) {
                claims.append(',');
            }
            claims.append("{\"id\":\"c").append(i).append("\",\"text\":\"claim ")
                    .append(i).append("\",\"verdict\":\"PASS\",\"evidenceRefs\":[\"completion\"]}");
        }
        fakeLlm.claimOutput = "{\"claims\":[" + claims + "],\"reason\":\"too many\"}";
        String datasetId = createDataset("claim-judge-overflow");
        addItem(datasetId, "q-overflow", "42");
        String evaluatorId = createEvaluator("claim-judge-overflow", claimSpec("platform/claim-v1",
                Map.of("maxClaims", 5, "requireEvidence", true,
                        "unknownPolicy", "BLOCK", "minPassRatio", 0.8)));
        String runId = runReplay(datasetId, evaluatorId, "claim-judge-run-overflow");
        JsonNode detail = awaitRun(datasetId, runId);

        JsonNode item = detail.path("data").path("items").get(0);
        // 超限不截断：截断可能丢掉 FAIL claim，整次判分安全失败为 UNKNOWN
        assertThat(item.path("label").asText()).isEqualTo("UNKNOWN");
        assertThat(item.path("evaluatorResults").get(0).path("reasonCode").asText())
                .isEqualTo("INVALID_JUDGE_OUTPUT");
        assertThat(detail.path("data").path("run").path("unknownCount").asInt()).isEqualTo(1);
    }

    @Test
    void missingRequiredEvidenceSkipsJudgeAndReturnsUnknown() throws Exception {
        String datasetId = createDataset("claim-judge-missing");
        addItem(datasetId, "q-missing", "42");
        Map<String, Object> spec = claimSpec("platform/claim-v1",
                Map.of("maxClaims", 5, "requireEvidence", true,
                        "unknownPolicy", "BLOCK", "minPassRatio", 0.8));
        spec.put("requiredFields", List.of("retrieval_context"));
        String evaluatorId = createEvaluator("claim-judge-missing", spec);
        int callsBefore = fakeLlm.calls.size();
        String runId = runReplay(datasetId, evaluatorId, "claim-judge-run-missing");
        JsonNode detail = awaitRun(datasetId, runId);

        JsonNode item = detail.path("data").path("items").get(0);
        assertThat(item.path("label").asText()).isEqualTo("UNKNOWN");
        assertThat(item.path("evaluatorResults").get(0).path("reasonCode").asText())
                .isEqualTo("RETRIEVAL_CONTEXT_MISSING");
        assertThat(item.path("evaluatorResults").get(0).path("judgeCalled").asBoolean()).isFalse();
        // 只剩 REPLAY 生成调用；缺证据时不得再发起 Judge 调用
        assertThat(fakeLlm.calls).hasSize(callsBefore + 1);
    }

    @Test
    void workspaceTemplateIsVersionedAndUsedByEvaluator() throws Exception {
        Map<String, Object> template = new HashMap<>();
        template.put("templateKey", "workspace/claim-custom");
        template.put("name", "custom claim judge");
        template.put("systemPrompt", "You are a strict evidence-based evaluator. Custom template.");
        template.put("outputSchema", "{\"claims\":\"array\"}");
        JsonNode created = readTree(postApi("/api/v1/judge-templates", template));
        assertThat(created.path("code").asInt()).isZero();
        assertThat(created.path("data").path("version").asInt()).isEqualTo(1);
        assertThat(created.path("data").path("fingerprint").asText()).hasSize(64);

        JsonNode list = readTree(get("/api/v1/judge-templates?key=workspace/claim-custom"));
        assertThat(list.path("data")).hasSize(1);
        assertThat(list.path("data").get(0).path("source").asText()).isEqualTo("WORKSPACE");

        String evaluatorId = createEvaluator("claim-judge-workspace",
                claimSpec("workspace/claim-custom", Map.of("maxClaims", 5)));
        JsonNode versions = readTree(get("/api/v1/evaluators/" + evaluatorId + "/versions"));
        JsonNode first = versions.path("data").get(0);
        assertThat(first.path("templateKey").asText()).isEqualTo("workspace/claim-custom");
        assertThat(first.path("templateHash").asText()).hasSize(64);
    }

    // ---------- helpers ----------

    private Map<String, Object> claimSpec(String templateKey, Map<String, Object> policy) {
        Map<String, Object> spec = new HashMap<>();
        spec.put("rubric", "判断候选答案是否有依据");
        spec.put("judgeMode", "CLAIMS");
        spec.put("judgeTemplateKey", templateKey);
        spec.put("claimsPolicy", policy);
        spec.put("referenceRequired", false);
        return spec;
    }

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private String createEvaluator(String key, Map<String, Object> spec) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/evaluators",
                Map.of("evaluatorKey", key, "type", "LLM_JUDGE", "spec", spec)));
        assertThat(response.path("code").asInt()).as(response.toString()).isZero();
        return response.path("data").path("id").asText();
    }

    private void addItem(String datasetId, String input, String expected) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("input", input, "expectedOutput", expected)));
        assertThat(response.path("code").asInt()).isZero();
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
