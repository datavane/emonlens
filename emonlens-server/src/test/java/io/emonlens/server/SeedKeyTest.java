package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SeedKeyRunner：emonlens.ingest.seed-key 启动时自动注册（生产 compose/env 注入的开箱即用路径）。
 */
@SpringBootTest(properties = {
        "emonlens.ingest.seed-key=smoke-seed-key-xyz",
        "spring.datasource.url=jdbc:h2:mem:emonlens-seedkey;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class SeedKeyTest extends AbstractIntegrationTest {

    @Test
    void configuredSeedKeyCanIngest() throws Exception {
        Map<String, Object> span = span("seed-agent", "seed-trace-1", "s1", null,
                "agent", "invoke", null, null, 1, 5);
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/ingest/events")
                        .header("X-Trust-Key", "smoke-seed-key-xyz")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(List.of(span))))
                .andReturn().getResponse().getContentAsString();
        JsonNode result = readTree(body);
        assertThat(result.path("code").asInt()).isZero();
        assertThat(result.path("data").path("acceptedCount").asInt()).isEqualTo(1);
    }

    @Test
    void unknownKeyIsRejected() throws Exception {
        Map<String, Object> span = span("seed-agent", "seed-trace-2", "s1", null,
                "agent", "invoke", null, null, 1, 5);
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/ingest/events")
                        .header("X-Trust-Key", "totally-unknown-key")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(List.of(span))))
                .andReturn().getResponse().getContentAsString();
        assertThat(readTree(body).path("code").asInt()).isEqualTo(40101);
    }
}
