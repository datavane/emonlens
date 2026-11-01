package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 固定窗口限流：第 3 个请求（limit=2）返回 50301。
 */
@SpringBootTest(properties = {
        "emonlens.security.rate-limit-per-minute=2",
        "spring.datasource.url=jdbc:h2:mem:emonlens-rate;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class RateLimitFilterTest extends AbstractIntegrationTest {

    @Test
    void thirdRequestIsRateLimited() throws Exception {
        for (int i = 0; i < 2; i++) {
            String ok = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/incidents")
                            .header("Authorization", adminAuth()))
                    .andReturn().getResponse().getContentAsString();
            assertThat(readSafely(ok).path("code").asInt()).isZero();
        }
        String limited = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/incidents")
                        .header("Authorization", adminAuth()))
                .andReturn().getResponse().getContentAsString();
        assertThat(readSafely(limited).path("code").asInt()).isEqualTo(50301);
    }

    private JsonNode readSafely(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
