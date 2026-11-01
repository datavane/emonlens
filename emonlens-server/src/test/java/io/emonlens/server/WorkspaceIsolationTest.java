package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.storage.entity.IncidentEntity;
import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.entity.UserEntity;
import io.emonlens.server.storage.entity.UserWorkspaceEntity;
import io.emonlens.server.storage.entity.WorkspaceEntity;
import io.emonlens.server.storage.mapper.IncidentMapper;
import io.emonlens.server.storage.mapper.SpanEventMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.mapper.UserMapper;
import io.emonlens.server.storage.mapper.UserWorkspaceMapper;
import io.emonlens.server.storage.mapper.WorkspaceMapper;

import javax.annotation.Resource;

/**
 * 行级隔离（评审 B2 验收）：另一工作空间的成员查询/读取本工作空间数据必须为空或 40401。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-isolation;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class WorkspaceIsolationTest extends AbstractIntegrationTest {

    @Resource
    private WorkspaceMapper workspaceMapper;

    @Resource
    private UserWorkspaceMapper userWorkspaceMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private TraceSummaryMapper traceSummaryMapper;

    @Resource
    private SpanEventMapper spanEventMapper;

    @Resource
    private IncidentMapper incidentMapper;

    @Test
    void otherWorkspaceMemberSeesNothing() throws Exception {
        // 1. admin 在 ws-default 创建 Monitor（写入真实数据）
        Map<String, Object> spec = new HashMap<>();
        spec.put("kind", "VALIDATION");
        spec.put("spanType", "llm");
        spec.put("where", "model_name != null");
        Map<String, Object> monitorRequest = new HashMap<>();
        monitorRequest.put("agentKey", "iso-agent");
        monitorRequest.put("monitorKey", "iso-monitor");
        monitorRequest.put("kind", "VALIDATION");
        monitorRequest.put("spec", spec);
        String created = postApi("/api/v1/monitors", monitorRequest);
        String monitorId = readSafely(created).path("data").path("id").asText();
        assertThat(monitorId).isNotEmpty();

        // 2. 构造第二个工作空间 + 成员用户（workspace 创建 API 属 P1，测试直接插库）
        WorkspaceEntity ws2 = new WorkspaceEntity();
        ws2.setId("ws-other-iso");
        ws2.setCode("other");
        ws2.setName("其他工作空间");
        ws2.setIsDefault(0);
        ws2.setCreatedAt(LocalDateTime.now());
        workspaceMapper.insert(ws2);

        Map<String, Object> userRequest = new HashMap<>();
        userRequest.put("username", "other-ws-user");
        userRequest.put("password", "other-123456");
        userRequest.put("name", "隔离用户");
        userRequest.put("roleId", "role-viewer");
        postApi("/api/v1/sys/users", userRequest);
        UserEntity otherUser = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, "other-ws-user"));

        UserWorkspaceEntity membership = new UserWorkspaceEntity();
        membership.setUserId(otherUser.getId());
        membership.setWorkspaceId("ws-other-iso");
        membership.setRoleId("role-viewer");
        membership.setCreatedAt(LocalDateTime.now());
        userWorkspaceMapper.insert(membership);

        // 3. other-ws 用户登录，切换到自己的工作空间
        String login = postApi("/api/v1/auth/login",
                Map.of("username", "other-ws-user", "password", "other-123456"));
        String otherToken = "Bearer " + readSafely(login).path("data").path("token").asText();

        // 4. 列表为空（行级隔离）
        String empty = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/monitors")
                        .header("Authorization", otherToken)
                        .header("X-Workspace-Id", "ws-other-iso"))
                .andReturn().getResponse().getContentAsString();
        assertThat(readSafely(empty).path("data").path("total").asLong()).isZero();

        // 5. 跨工作空间按 id 读取 → 40401（不泄露存在性）
        String denied = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/monitors/" + monitorId)
                        .header("Authorization", otherToken)
                        .header("X-Workspace-Id", "ws-other-iso"))
                .andReturn().getResponse().getContentAsString();
        assertThat(readSafely(denied).path("code").asInt()).isEqualTo(40401);

        // 6. admin 在自己工作空间仍可见（对照）
        String own = get("/api/v1/monitors/" + monitorId);
        assertThat(readSafely(own).path("data").path("id").asText()).isEqualTo(monitorId);
    }

    @Test
    void otherWorkspaceMemberCannotListEvaluatorsOrTestCrossWs() throws Exception {
        // admin 创建 evaluator（ws-default）
        Map<String, Object> spec = new HashMap<>();
        spec.put("passWhen", "error_count == 0");
        Map<String, Object> evaluatorRequest = new HashMap<>();
        evaluatorRequest.put("evaluatorKey", "iso-evaluator");
        evaluatorRequest.put("type", "EXPRESSION");
        evaluatorRequest.put("spec", spec);
        String created = postApi("/api/v1/evaluators", evaluatorRequest);
        String evaluatorId = readSafely(created).path("data").path("id").asText();

        // 构造 ws2 成员
        WorkspaceEntity ws2 = new WorkspaceEntity();
        ws2.setId("ws-other-eval");
        ws2.setCode("other-eval");
        ws2.setName("评估隔离工作空间");
        ws2.setIsDefault(0);
        ws2.setCreatedAt(LocalDateTime.now());
        workspaceMapper.insert(ws2);
        Map<String, Object> userRequest = new HashMap<>();
        userRequest.put("username", "eval-iso-user");
        userRequest.put("password", "eval-123456");
        userRequest.put("roleId", "role-operator");
        postApi("/api/v1/sys/users", userRequest);
        UserEntity otherUser = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, "eval-iso-user"));
        UserWorkspaceEntity membership = new UserWorkspaceEntity();
        membership.setUserId(otherUser.getId());
        membership.setWorkspaceId("ws-other-eval");
        membership.setRoleId("role-operator");
        membership.setCreatedAt(LocalDateTime.now());
        userWorkspaceMapper.insert(membership);
        String login = postApi("/api/v1/auth/login",
                Map.of("username", "eval-iso-user", "password", "eval-123456"));
        String otherToken = "Bearer " + readSafely(login).path("data").path("token").asText();

        // 列表隔离：ws2 视角 evaluators 为空
        String emptyList = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/evaluators")
                        .header("Authorization", otherToken)
                        .header("X-Workspace-Id", "ws-other-eval"))
                .andReturn().getResponse().getContentAsString();
        // ws2 视角：iso-evaluator（属于 ws-default）不可见
        JsonNode evalRecords = readSafely(emptyList).path("data").path("records");
        for (JsonNode record : evalRecords) {
            assertThat(record.path("evaluatorKey").asText()).isNotEqualTo("iso-evaluator");
        }

        // 跨工作空间 /test → 40401（不泄露 trace 存在性）
        String denied = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/evaluators/" + evaluatorId + "/test")
                        .header("Authorization", otherToken)
                        .contentType("application/json")
                        .content("{\"traceId\":\"c7a1d9e05f6b4a88\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(readSafely(denied).path("code").asInt()).isEqualTo(40401);

        // admin 对照：先在本工作空间接入一条 trace，test 正常。
        // ingest 是异步装配的：必须等 trace 真的可查再 test，否则命中 not-found(40401)
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span("iso-agent", "iso-trace-1", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        post("/api/v1/ingest/events", spans);
        await(
                () -> {
                    try {
                        return get("/api/v1/traces/iso-trace-1");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("summary").path("status").asText()
                        .equals("COMPLETED"),
                ASYNC_TIMEOUT_MS);
        String own = postApi("/api/v1/evaluators/" + evaluatorId + "/test",
                Map.of("traceId", "iso-trace-1"));
        assertThat(readSafely(own).path("code").asInt()).isZero();
    }

    @Test
    void sameTraceIdInTwoWorkspacesStaysIsolated() throws Exception {
        // admin 在 ws-default 写入 trace
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span("iso-agent", "iso-collide-trace", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        post("/api/v1/ingest/events", spans);
        await(() -> {
                    try {
                        return get("/api/v1/traces/iso-collide-trace");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("summary").path("status").asText()
                        .equals("COMPLETED"),
                ASYNC_TIMEOUT_MS);

        // 第二工作空间写入相同 trace_id 与 span_id：唯一键必须包含 workspace_id
        createWorkspace("ws-other-collide", "other-collide", "碰撞隔离工作空间");
        String otherToken = createMemberAndLogin("collide-iso-user", "collide-123456",
                "ws-other-collide", "role-operator");

        TraceSummaryEntity otherSummary = new TraceSummaryEntity();
        otherSummary.setWorkspaceId("ws-other-collide");
        otherSummary.setAgentKey("other-agent");
        otherSummary.setTraceId("iso-collide-trace");
        otherSummary.setStatus(TraceSummaryEntity.STATUS_COMPLETED);
        otherSummary.setSpanCount(1);
        otherSummary.setErrorCount(0);
        otherSummary.setStartTime(LocalDateTime.now());
        otherSummary.setLastSpanAt(LocalDateTime.now());
        otherSummary.setCompletedAt(LocalDateTime.now());
        traceSummaryMapper.insert(otherSummary);

        SpanEventEntity otherSpan = new SpanEventEntity();
        otherSpan.setWorkspaceId("ws-other-collide");
        otherSpan.setAgentKey("other-agent");
        otherSpan.setTraceId("iso-collide-trace");
        otherSpan.setSpanId("root"); // 与 ws-default 的 span_id 相同
        otherSpan.setSpanType("agent");
        otherSpan.setSpanName("invoke_agent");
        otherSpan.setStartTime(LocalDateTime.now());
        otherSpan.setEndTime(LocalDateTime.now());
        otherSpan.setStatus(SpanEventEntity.STATUS_OK);
        otherSpan.setIngestTime(LocalDateTime.now());
        spanEventMapper.insert(otherSpan);

        // admin 只看到自己工作空间的 summary / span
        JsonNode own = readSafely(get("/api/v1/traces/iso-collide-trace"));
        assertThat(own.path("data").path("summary").path("agentKey").asText()).isEqualTo("iso-agent");
        assertThat(own.path("data").path("spans").size()).isEqualTo(1);

        // ws2 用户只看到自己的 summary / span
        String other = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/traces/iso-collide-trace")
                        .header("Authorization", "Bearer " + otherToken)
                        .header("X-Workspace-Id", "ws-other-collide"))
                .andReturn().getResponse().getContentAsString();
        JsonNode otherDetail = readSafely(other);
        assertThat(otherDetail.path("data").path("summary").path("agentKey").asText())
                .isEqualTo("other-agent");
        assertThat(otherDetail.path("data").path("spans").size()).isEqualTo(1);
    }

    @Test
    void cannotAcknowledgeIncidentOfAnotherWorkspace() throws Exception {
        createWorkspace("ws-other-incident", "other-incident", "告警隔离工作空间");
        IncidentEntity incident = new IncidentEntity();
        incident.setWorkspaceId("ws-other-incident");
        incident.setAgentKey("other-agent");
        incident.setFingerprint("fp-other");
        incident.setTitle("other incident");
        incident.setSeverity("HIGH");
        incident.setStatus(IncidentEntity.STATUS_OPEN);
        incident.setFindingCount(0);
        incident.setFirstSeenAt(LocalDateTime.now());
        incident.setLastSeenAt(LocalDateTime.now());
        incident.setCreatedAt(LocalDateTime.now());
        incident.setUpdatedAt(LocalDateTime.now());
        incidentMapper.insert(incident);

        // admin（ws-default）按 id 迁移其他工作空间告警：必须拒绝，且状态不得被改写
        String denied = postApi("/api/v1/incidents/" + incident.getId() + "/ack", Map.of());
        assertThat(readSafely(denied).path("code").asInt()).isNotZero();
        assertThat(incidentMapper.selectById(incident.getId()).getStatus())
                .isEqualTo(IncidentEntity.STATUS_OPEN);
    }

    private void createWorkspace(String id, String code, String name) {
        WorkspaceEntity ws = new WorkspaceEntity();
        ws.setId(id);
        ws.setCode(code);
        ws.setName(name);
        ws.setIsDefault(0);
        ws.setCreatedAt(LocalDateTime.now());
        workspaceMapper.insert(ws);
    }

    private String createMemberAndLogin(String username, String password, String workspaceId,
                                        String roleId) throws Exception {
        Map<String, Object> userRequest = new HashMap<>();
        userRequest.put("username", username);
        userRequest.put("password", password);
        userRequest.put("name", username);
        userRequest.put("roleId", roleId);
        postApi("/api/v1/sys/users", userRequest);
        UserEntity user = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, username));
        UserWorkspaceEntity membership = new UserWorkspaceEntity();
        membership.setUserId(user.getId());
        membership.setWorkspaceId(workspaceId);
        membership.setRoleId(roleId);
        membership.setCreatedAt(LocalDateTime.now());
        userWorkspaceMapper.insert(membership);
        String login = postApi("/api/v1/auth/login",
                Map.of("username", username, "password", password));
        return readSafely(login).path("data").path("token").asText();
    }

    private JsonNode readSafely(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
