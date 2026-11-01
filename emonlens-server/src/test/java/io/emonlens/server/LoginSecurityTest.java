package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 登录安全加固：登出吊销（token 立即失效）+ 登录失败锁定（防爆破）。
 * 独立 H2 库 + 独立 Spring 上下文：LoginGuard 为实例内存态，避免影响其它测试类的登录。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-loginsec;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class LoginSecurityTest extends AbstractIntegrationTest {

    @Test
    void logoutRevokesTokenImmediately() throws Exception {
        String login = postApi("/api/v1/auth/login",
                Map.of("username", "admin", "password", "123456"));
        String token = readTree(login).path("data").path("token").asText();
        assertThat(token).isNotBlank();

        String me = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(readTree(me).path("code").asInt()).isZero();

        String logout = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(readTree(logout).path("code").asInt()).isZero();

        // 同一 token 复用被拒
        String reused = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(readTree(reused).path("code").asInt()).isEqualTo(40101);

        // 重新登录可用
        String relLogin = postApi("/api/v1/auth/login",
                Map.of("username", "admin", "password", "123456"));
        assertThat(readTree(relLogin).path("data").path("token").asText()).isNotBlank();
    }

    @Test
    void repeatedFailuresLockAccount() throws Exception {
        // 连续 5 次失败（针对专用用户名，不污染 admin 登录）
        for (int i = 0; i < 5; i++) {
            JsonNode r = readTree(loginAs("lockout-user", "bad-pass"));
            assertThat(r.path("code").asInt()).isEqualTo(40101);
        }
        // 第 6 次即使用正确密码也被锁定（50301）
        JsonNode locked = readTree(loginAs("lockout-user", "whatever"));
        assertThat(locked.path("code").asInt()).isEqualTo(50301);

        // 其他账号不受影响
        JsonNode other = readTree(postApi("/api/v1/auth/login",
                Map.of("username", "admin", "password", "123456")));
        assertThat(other.path("code").asInt()).isZero();
    }

    private String loginAs(String username, String password) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
    }
}
