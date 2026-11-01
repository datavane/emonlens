package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
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
 * 数据集 REPLAY 模式（回归测试语义）：
 * 样本输入 → FakeLLM 实际生成输出 → 落成新 trace（replay-*）→ 既有评估器打分 → run 记录重放输出与原始 trace。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-dsreplay;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class TraceDatasetReplayTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        LlmClient fakeLlmClient() {
            return (systemPrompt, userPrompt, model, temperature) -> "ECHO:" + userPrompt;
        }
    }

    @Test
    void replayRunGeneratesEvaluatesAndRecords() throws Exception {
        // 1. 评估器：REGEX 作用于 content kind 'completion'——同时验证重放输出进入了结构化内容库
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "REGEX");
        spec.put("field", "completion");
        spec.put("pattern", "ECHO");
        spec.put("requireMatch", true);
        Map<String, Object> evaluatorReq = new HashMap<>();
        evaluatorReq.put("evaluatorKey", "replay-echo");
        evaluatorReq.put("type", "REGEX");
        evaluatorReq.put("spec", spec);
        String evaluatorCreated = postApi("/api/v1/evaluators", evaluatorReq);
        String evaluatorId = readTree(evaluatorCreated).path("data").path("id").asText();
        assertThat(readTree(evaluatorCreated).path("code").asInt())
                .as("evaluator create: %s", evaluatorCreated).isZero();

        // 2. 数据集 + 仅带 input 的样本（无关联 trace——重放不依赖旧 trace）
        postApi("/api/v1/datasets", Map.of("datasetKey", "replay-ds", "description", "replay e2e"));
        JsonNode datasets = readTree(get("/api/v1/datasets"));
        String dsId = null;
        for (JsonNode d : datasets.path("data")) {
            if ("replay-ds".equals(d.path("datasetKey").asText())) {
                dsId = d.path("id").asText();
            }
        }
        assertThat(dsId).isNotNull();
        postApi("/api/v1/datasets/" + dsId + "/items", Map.of("input", "E2E replay question"));

        // 3. REPLAY 运行（同步完成）
        String runResp = postApi("/api/v1/datasets/" + dsId + "/runs",
                Map.of("evaluatorId", evaluatorId, "mode", "REPLAY"));
        JsonNode run = readTree(runResp).path("data");
        assertThat(readTree(runResp).path("code").asInt())
                .as("run: %s", runResp).isZero();
        assertThat(run.path("runMode").asText()).isEqualTo("REPLAY");
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("passCount").asInt()).isEqualTo(1);

        // 4. run item：记录重放 trace / 输出
        String detailResp = get("/api/v1/datasets/" + dsId + "/runs/" + run.path("id").asText());
        JsonNode detail = readTree(detailResp).path("data");
        JsonNode item = detail.path("items").get(0);
        assertThat(item).as("run detail: %s", detailResp).isNotNull();
        String replayTraceId = item.path("traceId").asText();
        assertThat(replayTraceId).startsWith("replay-");
        assertThat(item.path("replayOutput").asText()).isEqualTo("ECHO:E2E replay question");
        assertThat(item.path("label").asText()).isEqualTo("PASS");

        // 5. 重放产生的是一条真实可下钻的 trace（根 span + llm span，输出即生成内容）
        JsonNode traceDetail = readTree(get("/api/v1/traces/" + replayTraceId));
        JsonNode summary = traceDetail.path("data").path("summary");
        assertThat(summary.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(summary.path("agentKey").asText()).isEqualTo("dataset-replay");
        assertThat(traceDetail.path("data").path("spans").size()).isEqualTo(2);
    }
}
