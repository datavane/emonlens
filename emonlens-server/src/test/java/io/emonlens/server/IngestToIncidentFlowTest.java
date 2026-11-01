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
 * 端到端流程：ingest -> trace 组装完成 -> 事件消费 -> 检测 -> Finding -> Incident 归并 -> 状态机。
 * 验证设计文档 §3.4 数据流的核心闭环。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-flow;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class IngestToIncidentFlowTest extends AbstractIntegrationTest {

    private static final String AGENT = "flow-agent";

    @javax.annotation.Resource
    private io.emonlens.server.storage.mapper.EventMapper eventMapper;

    @Test
    void ingestToFindingToIncidentEndToEnd() throws Exception {
        // 1. 创建 Validation Monitor：禁止未批准模型
        Map<String, Object> spec = new HashMap<>();
        spec.put("kind", "VALIDATION");
        spec.put("spanType", "llm");
        spec.put("where", "model_name != null && model_name != 'approved-model-a'");
        Map<String, Object> createRequest = new HashMap<>();
        createRequest.put("agentKey", AGENT);
        createRequest.put("monitorKey", "model-allow-list");
        createRequest.put("kind", "VALIDATION");
        createRequest.put("spec", spec);
        createRequest.put("severity", "HIGH");
        String created = postApi("/api/v1/monitors", createRequest);
        assertThat(readTree(created).path("code").asInt()).isZero();

        // 2. ingest 一个已完成的 trace：根 agent span + 违规 llm span
        String traceId = "flow-trace-001";
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span(AGENT, traceId, "root-1", null, "agent", "invoke_agent", null, null, 1, 10));
        spans.add(span(AGENT, traceId, "llm-1", "root-1", "llm", "call_model", null, "gpt-unapproved", 2, 8));
        String ingestResult = post("/api/v1/ingest/events", spans);
        JsonNode ingestJson = readTree(ingestResult);
        assertThat(ingestJson.path("code").asInt()).isZero();
        assertThat(ingestJson.path("data").path("acceptedCount").asInt()).isEqualTo(2);

        // 3. 等待 trace 完成（根 span 已结束 -> 瞬时完成）
        String detail = await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + traceId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("summary").path("status").asText()
                        .equals("COMPLETED"),
                ASYNC_TIMEOUT_MS);

        // 4. 等待检测产出 Finding
        String findingsJson = await(
                () -> {
                    try {
                        return get("/api/v1/findings?traceId=" + traceId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("total").asLong() >= 1,
                ASYNC_TIMEOUT_MS);
        JsonNode finding = readSafely(findingsJson).path("data").path("records").get(0);
        assertThat(finding.path("type").asText()).isEqualTo("MONITOR_VALIDATION");
        assertThat(finding.path("detectorId").asText()).isEqualTo("model-allow-list");
        assertThat(finding.path("severity").asText()).isEqualTo("HIGH");

        // 5. 等待 Incident 自动归并
        String incidentsJson = await(
                () -> {
                    try {
                        return get("/api/v1/incidents?agentKey=" + AGENT);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("total").asLong() >= 1,
                ASYNC_TIMEOUT_MS);
        String incidentId = readSafely(incidentsJson).path("data").path("records").get(0).path("id").asText();

        // 6. 状态机：ack -> resolve；重复 ack 应冲突
        String acked = postApi("/api/v1/incidents/" + incidentId + "/ack", new HashMap<>());
        assertThat(readTree(acked).path("data").path("status").asText()).isEqualTo("ACKNOWLEDGED");
        String ackAgain = postApi("/api/v1/incidents/" + incidentId + "/ack", new HashMap<>());
        assertThat(readTree(ackAgain).path("code").asInt()).isEqualTo(40901);
        String resolved = postApi("/api/v1/incidents/" + incidentId + "/resolve", new HashMap<>());
        assertThat(readTree(resolved).path("data").path("status").asText()).isEqualTo("RESOLVED");

        // 7. trace detail 应包含 spans 与 findings（重新拉取，检测为完成后异步执行）
        String latestDetail = get("/api/v1/traces/" + traceId);
        JsonNode detailJson = readSafely(latestDetail);
        assertThat(detailJson.path("data").path("spans").size()).isEqualTo(2);
        assertThat(detailJson.path("data").path("findings").size()).isEqualTo(1);
    }

    @Test
    void sameFingerprintAfterAckCanOpenAndAckAgain() throws Exception {
        // 回归：旧唯一键 (workspace_id, agent_key, fingerprint, status) 下，
        // 同指纹第一个告警已 ACK 后再触发新 OPEN 告警，第二次 ACK 会与旧 ACKNOWLEDGED
        // 行撞唯一键返回 500。改成“仅 OPEN 占用 open_fp”后应完整走通两次生命周期。
        String agent = "flow-agent-repeat";
        String firstTrace = "flow-repeat-trace-1";
        ingestBehaviorLoopTrace(agent, firstTrace);

        String firstList = await(
                () -> {
                    try {
                        return get("/api/v1/incidents?agentKey=" + agent);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("total").asLong() >= 1,
                ASYNC_TIMEOUT_MS);
        String firstId = readSafely(firstList).path("data").path("records").get(0).path("id").asText();

        JsonNode firstAck = readTree(postApi("/api/v1/incidents/" + firstId + "/ack", new HashMap<>()));
        assertThat(firstAck.path("code").asInt()).isZero();
        assertThat(firstAck.path("data").path("status").asText()).isEqualTo("ACKNOWLEDGED");

        // 第一条告警已 ACK，新 trace 的同指纹 finding 应新建一个 OPEN 告警
        String secondTrace = "flow-repeat-trace-2";
        ingestBehaviorLoopTrace(agent, secondTrace);
        String secondList = await(
                () -> {
                    try {
                        return get("/api/v1/incidents?agentKey=" + agent);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    JsonNode data = readSafely(json).path("data");
                    if (data.path("total").asLong() < 2) {
                        return false;
                    }
                    for (JsonNode record : data.path("records")) {
                        if ("OPEN".equals(record.path("status").asText())
                                && !firstId.equals(record.path("id").asText())) {
                            return true;
                        }
                    }
                    return false;
                },
                ASYNC_TIMEOUT_MS);
        String secondId = null;
        for (JsonNode record : readSafely(secondList).path("data").path("records")) {
            if ("OPEN".equals(record.path("status").asText())
                    && !firstId.equals(record.path("id").asText())) {
                secondId = record.path("id").asText();
                break;
            }
        }
        assertThat(secondId).isNotNull();

        JsonNode secondAck = readTree(postApi("/api/v1/incidents/" + secondId + "/ack", new HashMap<>()));
        assertThat(secondAck.path("code").asInt()).isZero();
        assertThat(secondAck.path("data").path("status").asText()).isEqualTo("ACKNOWLEDGED");

        // 两条 ACKNOWLEDGED 告警先后 RESOLVE 也不应撞唯一键
        JsonNode firstResolved = readTree(postApi("/api/v1/incidents/" + firstId + "/resolve", new HashMap<>()));
        assertThat(firstResolved.path("code").asInt()).isZero();
        assertThat(firstResolved.path("data").path("status").asText()).isEqualTo("RESOLVED");
        JsonNode secondResolved = readTree(postApi("/api/v1/incidents/" + secondId + "/resolve", new HashMap<>()));
        assertThat(secondResolved.path("code").asInt()).isZero();
        assertThat(secondResolved.path("data").path("status").asText()).isEqualTo("RESOLVED");
    }

    private void ingestBehaviorLoopTrace(String agent, String traceId) throws Exception {
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span(agent, traceId, traceId + "-root", null, "agent", "invoke_agent", null, null, 1, 20));
        for (int i = 1; i <= 3; i++) {
            spans.add(span(agent, traceId, traceId + "-t" + i, traceId + "-root", "tool",
                    "query_ticket", "query_ticket", null, i, i + 1));
        }
        JsonNode ingest = readTree(post("/api/v1/ingest/events", spans));
        assertThat(ingest.path("code").asInt()).isZero();
    }

    @Test
    void eventPayloadEscapesQuotedAgentKey() throws Exception {
        // agentKey 来自上报数据且只校验长度：含引号时必须是合法 JSON（历史实现用字符串拼接会产出坏 JSON，
        // 下游通知/评分 fan-out 解析 payload 直接失败）
        String quotedAgent = "flow\"agent:quoted";
        String traceId = "flow-trace-quoted-agent";
        ingestBehaviorLoopTrace(quotedAgent, traceId);

        String findingsJson = await(
                () -> {
                    try {
                        return get("/api/v1/findings?traceId=" + traceId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("total").asLong() >= 1,
                ASYNC_TIMEOUT_MS);
        assertThat(readSafely(findingsJson).path("data").path("records").get(0)
                .path("agentKey").asText()).isEqualTo(quotedAgent);

        // et_event 中 FINDING_CREATED 的 payload 必须可解析，且 agentKey 完整保留
        JsonNode payload = await(
                () -> {
                    List<io.emonlens.server.storage.entity.EventEntity> rows =
                            eventMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                                    .<io.emonlens.server.storage.entity.EventEntity>lambdaQuery()
                                    .eq(io.emonlens.server.storage.entity.EventEntity::getEventType,
                                            "FINDING_CREATED")
                                    .orderByDesc(io.emonlens.server.storage.entity.EventEntity::getCreatedAt)
                                    .last("LIMIT 5"));
                    for (io.emonlens.server.storage.entity.EventEntity row : rows) {
                        try {
                            JsonNode node = objectMapper.readTree(row.getPayload());
                            if (traceId.equals(node.path("traceId").asText())) {
                                return node;
                            }
                        } catch (Exception e) {
                            throw new AssertionError("event payload is not valid JSON: "
                                    + row.getPayload(), e);
                        }
                    }
                    return null;
                },
                node -> node != null,
                ASYNC_TIMEOUT_MS);
        assertThat(payload.path("agentKey").asText()).isEqualTo(quotedAgent);
    }

    @Test
    void behaviorLoopDetectionProducesFinding() throws Exception {
        // 相同 tool 连续重复 3 次 -> 内置 Behavior Loop 检测（无需配置 Monitor）
        String traceId = "flow-trace-loop";
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span(AGENT, traceId, "loop-root", null, "agent", "invoke_agent", null, null, 1, 20));
        for (int i = 1; i <= 3; i++) {
            spans.add(span(AGENT, traceId, "loop-t" + i, "loop-root", "tool", "query_ticket", "query_ticket",
                    null, i, i + 1));
        }
        post("/api/v1/ingest/events", spans);

        String findingsJson = await(
                () -> {
                    try {
                        return get("/api/v1/findings?traceId=" + traceId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("total").asLong() >= 1,
                ASYNC_TIMEOUT_MS);
        JsonNode finding = readSafely(findingsJson).path("data").path("records").get(0);
        assertThat(finding.path("type").asText()).isEqualTo("BEHAVIOR_LOOP");
        assertThat(finding.path("dimension").asText()).isEqualTo("BEHAVIOR");
        assertThat(finding.path("evidence").asText()).contains("EXACT_REPEAT");
    }

    private JsonNode readSafely(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
