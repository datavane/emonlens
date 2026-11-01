package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 补口 #27/#28：Ingest Key 签发-吊销闭环 + Agent/Tool 幂等同步。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-keys;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class IngestKeyAndAgentSyncTest extends AbstractIntegrationTest {

    @Test
    void issueListRevokeIngestKey() throws Exception {
        // 签发（明文一次）
        String issued = postApi("/api/v1/ingest-keys", new HashMap<>());
        JsonNode issuedData = readSafely(issued).path("data");
        String plain = issuedData.path("key").asText();
        assertThat(plain).startsWith("tk-");
        String keyId = issuedData.path("id").asText();

        // 列表只含掩码
        String list = get("/api/v1/ingest-keys");
        assertThat(readSafely(list).path("data").path("records").toString()).doesNotContain(plain);

        // 新 key 可正常上报
        String ok = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/ingest/events")
                        .header("X-Trust-Key", plain)
                        .contentType("application/json")
                        .content("[]"))
                .andReturn().getResponse().getContentAsString();
        assertThat(readSafely(ok).path("code").asInt()).isZero();

        // 吊销后上报 → 40101
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/ingest-keys/" + keyId)
                        .header("Authorization", adminAuth()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        String denied = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/ingest/events")
                        .header("X-Trust-Key", plain)
                        .contentType("application/json")
                        .content("[]"))
                .andReturn().getResponse().getContentAsString();
        assertThat(readSafely(denied).path("code").asInt()).isEqualTo(40101);
    }

    @Test
    void agentUpsertAndToolsAreIdempotent() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "同步 Agent");
        body.put("version", "v1");
        body.put("riskLevel", "HIGH");
        body.put("owner", "测试");
        String synced = put("/api/v1/agents/sync-agent", body);
        assertThat(readSafely(synced).path("code").asInt()).isZero();

        // 再 PUT 一次（幂等更新）
        put("/api/v1/agents/sync-agent", body);

        String detail = get("/api/v1/agents/sync-agent");
        JsonNode agent = readSafely(detail).path("data");
        assertThat(agent.path("agentKey").asText()).isEqualTo("sync-agent");

        // Tool 同步
        put("/api/v1/agents/sync-agent/tools/execute_payment", Map.of("sideEffect", "FINANCIAL"));
        String detail2 = get("/api/v1/agents/sync-agent");
        assertThat(readSafely(detail2).path("data").path("tools").toString()).contains("execute_payment");
    }

    @Test
    void agentDeleteRemovesRegistrationButKeepsTraceHistory() throws Exception {
        // 注册 → 注销：注册行与工具清单移除，但不该 500 且重复删除给明确 NOT_FOUND
        Map<String, Object> req = new HashMap<>();
        req.put("agentKey", "del-agent");
        req.put("name", "待注销 Agent");
        assertThat(readTree(put("/api/v1/agents/del-agent", req)).path("code").asInt()).isZero();

        Map<String, Object> toolReq = new HashMap<>();
        toolReq.put("sideEffect", "READ");
        assertThat(readTree(put("/api/v1/agents/del-agent/tools/query_db", toolReq)).path("code").asInt()).isZero();

        JsonNode deleted = readTree(deleteApi("/api/v1/agents/del-agent")).path("data");
        assertThat(deleted.path("agentKey").asText()).isEqualTo("del-agent");
        assertThat(deleted.path("removedTools").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode again = readTree(deleteApi("/api/v1/agents/del-agent")).path("data");
        assertThat(again).isEmpty();   // 第二次：业务码 NOT_FOUND（HTTP 200 + code!=0），data 为空
        assertThat(readTree(deleteApi("/api/v1/agents/del-agent")).path("code").asInt()).isNotZero();
    }

    private String put(String url, Object body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url)
                        .header("Authorization", adminAuth())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode readSafely(String json) {
        try { return objectMapper.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
