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
 * 查询 API 契约：traces/search 过滤与分页、ingest 鉴权失败路径。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-flow;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class TraceSearchApiTest extends AbstractIntegrationTest {

    @Test
    void searchFiltersAndPagination() throws Exception {
        // 三个不同 agent 的 trace
        for (int t = 0; t < 3; t++) {
            String traceId = "search-trace-" + t;
            List<Map<String, Object>> spans = new ArrayList<>();
            spans.add(span("search-agent", traceId, "s-root-" + t, null, "agent", "invoke_agent", null, null, 1, 5));
            post("/api/v1/ingest/events", spans);
        }

        // 等待三条 trace 落库
        await(
                () -> {
                    try {
                        return search("search-agent", null);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("total").asLong() >= 3,
                ASYNC_TIMEOUT_MS);

        // agentKey 过滤
        JsonNode filtered = readSafely(search("search-agent", null));
        assertThat(filtered.path("data").path("total").asLong()).isEqualTo(3);
        for (JsonNode record : filtered.path("data").path("records")) {
            assertThat(record.path("agentKey").asText()).isEqualTo("search-agent");
        }

        // 分页
        JsonNode paged = readSafely(search("search-agent", 1));
        assertThat(paged.path("data").path("pageSize").asLong()).isEqualTo(1);
        assertThat(paged.path("data").path("records").size()).isEqualTo(1);
        assertThat(paged.path("data").path("total").asLong()).isEqualTo(3);

        // trace detail
        String detail = get("/api/v1/traces/search-trace-0");
        JsonNode detailJson = readSafely(detail);
        assertThat(detailJson.path("data").path("summary").path("traceId").asText())
                .isEqualTo("search-trace-0");
        assertThat(detailJson.path("data").path("spans").size()).isEqualTo(1);
    }

    @Test
    void ingestRejectsInvalidKey() throws Exception {
        String response = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/ingest/events")
                        .header("X-Trust-Key", "wrong-key")
                        .contentType("application/json")
                        .content("[]"))
                .andReturn().getResponse().getContentAsString();
        assertThat(readSafely(response).path("code").asInt()).isEqualTo(40101);
    }

    private String search(String agentKey, Integer pageSize) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("agentKeys", List.of(agentKey));
        request.put("pageNumber", 1);
        request.put("pageSize", pageSize == null ? 20 : pageSize);
        return postApi("/api/v1/traces/search", request);
    }

    private JsonNode readSafely(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
