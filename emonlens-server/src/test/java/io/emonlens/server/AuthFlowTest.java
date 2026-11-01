package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 独立产品认证基线：登录/未登录/权限/工作空间成员校验。
 * 独立 H2 库避免与其它上下文相互干扰。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-auth;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class AuthFlowTest extends AbstractIntegrationTest {

    @Test
    void loginRejectsWrongPassword() throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(readSafely(body).path("code").asInt()).isEqualTo(40101);
    }

    @Test
    void businessApiRejectsAnonymous() throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/incidents"))
                .andReturn().getResponse().getContentAsString();
        assertThat(readSafely(body).path("code").asInt()).isEqualTo(40101);
    }

    @Test
    void loginSucceedsAndMeReturnsIdentity() throws Exception {
        String body = postApi("/api/v1/auth/login",
                Map.of("username", "admin", "password", "123456"));
        JsonNode data = readSafely(body).path("data");
        assertThat(data.path("token").asText()).isNotEmpty();
        assertThat(data.path("user").path("username").asText()).isEqualTo("admin");
        assertThat(data.path("workspace").path("id").asText()).isEqualTo("ws-default");
        assertThat(data.path("permissions").size()).isGreaterThan(0);

        String me = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + data.path("token").asText()))
                .andReturn().getResponse().getContentAsString();
        assertThat(readSafely(me).path("data").path("user").path("username").asText()).isEqualTo("admin");
    }

    @Test
    void viewerRoleCannotWriteMonitors() throws Exception {
        // 创建 viewer 用户（admin 操作）→ viewer 登录 → 写接口 40301
        Map<String, Object> create = new HashMap<>();
        create.put("username", "viewer1");
        create.put("password", "viewer123");
        create.put("name", "只读用户");
        create.put("roleId", "role-viewer");
        String created = postApi("/api/v1/sys/users", create);
        assertThat(readSafely(created).path("code").asInt()).isZero();

        String viewerLogin = postApi("/api/v1/auth/login",
                Map.of("username", "viewer1", "password", "viewer123"));
        String viewerToken = "Bearer " + readSafely(viewerLogin).path("data").path("token").asText();

        Map<String, Object> monitorRequest = new HashMap<>();
        monitorRequest.put("agentKey", "auth-agent");
        monitorRequest.put("monitorKey", "no-write");
        monitorRequest.put("kind", "VALIDATION");
        monitorRequest.put("spec", Map.of("kind", "VALIDATION", "where", "error_count == 0"));
        String denied = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/monitors")
                        .header("Authorization", viewerToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(monitorRequest)))
                .andReturn().getResponse().getContentAsString();
        assertThat(readSafely(denied).path("code").asInt()).isEqualTo(40301);
    }

    @Test
    void nonMemberWorkspaceIsRejected() throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/incidents")
                        .header("Authorization", adminAuth())
                        .header("X-Workspace-Id", "ws-other"))
                .andReturn().getResponse().getContentAsString();
        assertThat(readSafely(body).path("code").asInt()).isEqualTo(40301);
    }

    @Test
    void userCreationRejectsWeakPasswordAndOversizedUsername() throws Exception {
        // 密码过短（<8）→ 40000，且不落库
        JsonNode weak = readSafely(postApi("/api/v1/sys/users",
                Map.of("username", "weak-pw", "password", "1234567")));
        assertThat(weak.path("code").asInt()).isEqualTo(40000);
        assertThat(weak.path("message").asText()).contains("password");

        // 超长用户名 → 40000
        JsonNode oversized = readSafely(postApi("/api/v1/sys/users",
                Map.of("username", "u".repeat(65), "password", "long-enough-pw")));
        assertThat(oversized.path("code").asInt()).isEqualTo(40000);
        assertThat(oversized.path("message").asText()).contains("username");

        // 边界：8 位密码合法
        JsonNode ok = readSafely(postApi("/api/v1/sys/users",
                Map.of("username", "pw-boundary", "password", "12345678")));
        assertThat(ok.path("code").asInt()).isZero();

        // 重置密码同样受强度约束
        String userId = ok.path("data").path("id").asText();
        JsonNode weakReset = readSafely(putApi("/api/v1/sys/users/" + userId + "/password",
                Map.of("password", "short")));
        assertThat(weakReset.path("code").asInt()).isEqualTo(40000);
    }

    private JsonNode readSafely(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
