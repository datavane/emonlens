package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sessions（会话一等公民）：同 sessionId 的 trace 聚合为会话列表/详情（Langfuse Sessions 对标）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-sessions;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class SessionsApiTest extends AbstractIntegrationTest {

    @Test
    void sessionListAndDetailAggregateBySessionId() throws Exception {
        Map<String, Object> root1 = span("sess-agent", "sess-t-1", "root", null, "agent", "invoke", null, null, 1, 10);
        root1.put("sessionId", "sess-e2e");
        root1.put("userId", "user-a");
        post("/api/v1/ingest/events", List.of(root1));
        Map<String, Object> root2 = span("sess-agent", "sess-t-2", "root", null, "agent", "invoke", null, null, 20, 30);
        root2.put("sessionId", "sess-e2e");
        post("/api/v1/ingest/events", List.of(root2));

        // 列表：等**本方法自己**的 session 装配完成。
        // 不能只等 total >= 1——同一内存 H2 里可能已有其它方法遗留的 session，
        // 提前返回时本方法的 span 还没落库，后面断言必然扑空
        String sessionsResp = await(
                () -> {
                    try {
                        return get("/api/v1/sessions?pageNumber=1&pageSize=20");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    for (JsonNode r : readTreeSafe(json).path("data").path("records")) {
                        if ("sess-e2e".equals(r.path("sessionId").asText())
                                && r.path("traceCount").asInt() >= 2) {
                            return true;
                        }
                    }
                    return false;
                },
                ASYNC_TIMEOUT_MS);
        JsonNode sessions = readTreeSafe(sessionsResp);
        JsonNode hit = null;
        for (JsonNode r : sessions.path("data").path("records")) {
            if ("sess-e2e".equals(r.path("sessionId").asText())) {
                hit = r;
            }
        }
        assertThat(hit).isNotNull();
        assertThat(hit.path("traceCount").asInt()).isEqualTo(2);
        assertThat(hit.path("userIds").isArray()).isTrue();
        assertThat(hit.path("userIds").get(0).asText()).isEqualTo("user-a");

        // 详情：会话内全部 trace，按时间正序
        JsonNode detail = readTreeSafe(get("/api/v1/sessions/sess-e2e"));
        assertThat(detail.path("data").path("traceCount").asInt()).isEqualTo(2);
        JsonNode traces = detail.path("data").path("traces");
        assertThat(traces.size()).isEqualTo(2);
        assertThat(traces.get(0).path("traceId").asText()).isEqualTo("sess-t-1");
        assertThat(traces.get(1).path("traceId").asText()).isEqualTo("sess-t-2");

        // trace 搜索支持 sessionId 过滤
        String searchBody = "{\"sessionId\":\"sess-e2e\",\"pageNumber\":1,\"pageSize\":10}";
        JsonNode search = readTree(postApi("/api/v1/traces/search", objectMapper.readTree(searchBody)));
        assertThat(search.path("data").path("total").asInt()).isEqualTo(2);
    }

    @Test
    void cachedInputTokensAggregateAndExposeOnSpan() throws Exception {
        Map<String, Object> llm = span("cache-agent", "cache-t-1", "llm-1", "root", "llm", "call", null, "m1", 1, 2);
        llm.put("sessionId", "sess-cache");
        llm.put("cachedInputTokens", 800);
        Map<String, Object> root = span("cache-agent", "cache-t-1", "root", null, "agent", "invoke", null, null, 1, 3);
        root.put("sessionId", "sess-cache");
        post("/api/v1/ingest/events", List.of(llm, root));

        // trace 详情的 llm span 透出一等字段 cachedInputTokens
        String resp = await(
                () -> {
                    try {
                        return get("/api/v1/traces/cache-t-1");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readTreeSafe(json).path("data").path("spans").size() >= 2,
                ASYNC_TIMEOUT_MS);
        JsonNode spans = readTreeSafe(resp).path("data").path("spans");
        JsonNode llmVo = null;
        for (JsonNode s : spans) {
            if ("llm".equals(s.path("spanType").asText())) {
                llmVo = s;
            }
        }
        assertThat(llmVo).isNotNull();
        assertThat(llmVo.path("inputTokens").asLong()).isEqualTo(100);
        assertThat(llmVo.path("cachedInputTokens").asLong()).isEqualTo(800);

        // 会话详情汇总缓存命中量
        String sessResp = await(
                () -> {
                    try {
                        return get("/api/v1/sessions/sess-cache");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readTreeSafe(json).path("data").path("traceCount").asInt() >= 1,
                ASYNC_TIMEOUT_MS);
        JsonNode detail = readTreeSafe(sessResp).path("data");
        assertThat(detail.path("cachedInputTokens").asLong()).isEqualTo(800);
    }

    @Test
    void sessionListSupportsFiltersQueryAndPagination() throws Exception {
        Map<String, Object> a = span("agent-filter-a", "sess-filter-t1", "root", null,
                "agent", "invoke", null, null, 1, 5);
        a.put("sessionId", "sess-filter-a");
        a.put("userId", "user-filter-a");
        Map<String, Object> b = span("agent-filter-b", "sess-filter-t2", "root", null,
                "agent", "invoke", null, null, 2, 6);
        b.put("sessionId", "sess-filter-b");
        b.put("userId", "user-filter-b");
        b.put("status", "ERROR");
        post("/api/v1/ingest/events", List.of(a));
        post("/api/v1/ingest/events", List.of(b));

        // 等两条 trace 都完成会话聚合（本类 H2 跨方法共享，必须按前缀过滤隔离）
        String all = await(
                () -> {
                    try {
                        return get("/api/v1/sessions?query=sess-filter&pageNumber=1&pageSize=10");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readTreeSafe(json).path("data").path("total").asLong() >= 2,
                ASYNC_TIMEOUT_MS);
        JsonNode allData = readTreeSafe(all).path("data");
        assertThat(allData.path("total").asLong()).isEqualTo(2);

        // 分页：lastActivity 倒序，pageSize=1 时第 1 页是较新的 sess-filter-b
        JsonNode firstPage = readTreeSafe(get(
                "/api/v1/sessions?query=sess-filter&pageNumber=1&pageSize=1")).path("data");
        assertThat(firstPage.path("total").asLong()).isEqualTo(2);
        assertThat(firstPage.path("records").size()).isEqualTo(1);
        assertThat(firstPage.path("records").get(0).path("sessionId").asText()).isEqualTo("sess-filter-b");
        JsonNode secondPage = readTreeSafe(get(
                "/api/v1/sessions?query=sess-filter&pageNumber=2&pageSize=1")).path("data");
        assertThat(secondPage.path("records").get(0).path("sessionId").asText()).isEqualTo("sess-filter-a");

        // agent / user / hasError 过滤
        JsonNode byAgent = readTreeSafe(get(
                "/api/v1/sessions?query=sess-filter&agentKey=agent-filter-a")).path("data");
        assertThat(byAgent.path("total").asLong()).isEqualTo(1);
        assertThat(byAgent.path("records").get(0).path("sessionId").asText()).isEqualTo("sess-filter-a");

        JsonNode byUser = readTreeSafe(get(
                "/api/v1/sessions?query=sess-filter&userId=user-filter-b")).path("data");
        assertThat(byUser.path("total").asLong()).isEqualTo(1);
        assertThat(byUser.path("records").get(0).path("errorCount").asLong()).isEqualTo(1);

        JsonNode errorOnly = readTreeSafe(get(
                "/api/v1/sessions?query=sess-filter&hasError=true")).path("data");
        assertThat(errorOnly.path("total").asLong()).isEqualTo(1);
        assertThat(errorOnly.path("records").get(0).path("sessionId").asText()).isEqualTo("sess-filter-b");
    }

    private JsonNode readTreeSafe(String json) {
        try {
            return readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
