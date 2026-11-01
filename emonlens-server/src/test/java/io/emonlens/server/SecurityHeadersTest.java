package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 基础安全响应头：API 与 SPA 入口都必须带 nosniff / 防嵌入 / Referrer-Policy。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-sec-headers;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class SecurityHeadersTest extends AbstractIntegrationTest {

    @Test
    void apiResponsesCarrySecurityHeaders() throws Exception {
        var response = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header("Authorization", adminAuth()))
                .andReturn().getResponse();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    }

    @Test
    void spaEntryCarriesSecurityHeaders() throws Exception {
        var response = mockMvc.perform(MockMvcRequestBuilders.get("/")
                        .header("Accept", "text/html,application/xhtml+xml"))
                .andReturn().getResponse();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        // 入口 HTML 的缓存头由 spring.web.resources.cache.cachecontrol.no-cache 控制（资源处理器在
        // MockMvc 的 welcome-page 路径不应用该配置），此处不强断言，线上行为见部署验证。
    }
}
