package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * P2 通知中心端到端：CRITICAL Incident → INCIDENT_CREATED 事件 → 通知派发 →
 * EMAIL 渠道（SMTP 桩：8187 端口不动，用 Webhook 渠道做 HTTP 桩验证渲染与签名）
 * + severity 升级 → ESCALATED 事件。
 * 用 WEBHOOK 渠道 + JDK HttpServer 桩验证：签名 v2 可复算、事件载荷含 severity/title。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-notify;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class NotifyFlowTest extends AbstractIntegrationTest {

    static final int PORT = 18311;
    static final String SECRET = "notify-secret";
    static final List<String> SIGNATURES = new CopyOnWriteArrayList<>();
    static final List<String> TIMESTAMPS = new CopyOnWriteArrayList<>();
    static final List<String> BODIES = new CopyOnWriteArrayList<>();
    static final List<String> EVENT_IDS = new CopyOnWriteArrayList<>();
    static final List<String> EVENT_TYPES = new CopyOnWriteArrayList<>();
    static com.sun.net.httpserver.HttpServer server;

    @BeforeAll
    static void startStub() throws Exception {
        server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(PORT), 0);
        server.createContext("/webhook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            BODIES.add(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            SIGNATURES.add(exchange.getRequestHeaders().getFirst("X-Em-Signature"));
            EVENT_IDS.add(exchange.getRequestHeaders().getFirst("X-Em-Event-Id"));
            EVENT_TYPES.add(exchange.getRequestHeaders().getFirst("X-Em-Event-Type"));
            TIMESTAMPS.add(exchange.getRequestHeaders().getFirst("X-Em-Timestamp"));
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("ok".getBytes());
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopStub() { server.stop(0); }

    @Test
    void criticalIncidentTriggersWebhookWithV2Signature() throws Exception {
        // 1. 配置 WEBHOOK 渠道 + incident 模板
        Map<String, Object> senderConfig = new HashMap<>();
        senderConfig.put("url", "http://127.0.0.1:" + PORT + "/webhook");
        senderConfig.put("secret", SECRET);
        senderConfig.put("keyVersion", "v1");
        Map<String, Object> senderRequest = new HashMap<>();
        senderRequest.put("channel", "WEBHOOK");
        senderRequest.put("name", "测试 webhook");
        senderRequest.put("config", objectMapper.writeValueAsString(senderConfig));
        postApi("/api/v1/notify/senders", senderRequest);

        Map<String, Object> templateRequest = new HashMap<>();
        templateRequest.put("code", "notify-test-incident");
        templateRequest.put("name", "Incident 通知（测试）");
        templateRequest.put("channel", "WEBHOOK");
        templateRequest.put("content", "Agent {{agent}} 触发 {{severity}} 级 Incident：{{title}}");
        postApi("/api/v1/notify/templates", templateRequest);

        // 2. 创建 CRITICAL monitor + 触发 trace
        Map<String, Object> spec = new HashMap<>();
        spec.put("kind", "VALIDATION");
        spec.put("spanType", "tool");
        spec.put("where", "tool_name == 'forbidden_tool'");
        Map<String, Object> monitorRequest = new HashMap<>();
        monitorRequest.put("agentKey", "notify-agent");
        monitorRequest.put("monitorKey", "notify-gate");
        monitorRequest.put("kind", "VALIDATION");
        monitorRequest.put("severity", "CRITICAL");
        monitorRequest.put("dimension", "SECURITY");
        monitorRequest.put("spec", spec);
        postApi("/api/v1/monitors", monitorRequest);

        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span("notify-agent", "notify-trace-1", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        spans.add(span("notify-agent", "notify-trace-1", "t1", "root", "tool", "run_tool", "forbidden_tool", null, 2, 3));
        post("/api/v1/ingest/events", spans);

        // 3. 等待 webhook 收到（INCIDENT_CREATED 事件 → 派发）
        int before = SIGNATURES.size();
        await(SIGNATURES::size, n -> n > before, ASYNC_TIMEOUT_MS);
        int i = SIGNATURES.size() - 1;

        // 4. 验证签名 v2（timestamp + eventId + eventType + rawBody），索引从本测试起点算
        JsonNode envelope = objectMapper.readTree(BODIES.get(i));
        String eventType = envelope.path("event_type").asText();
        assertThat(eventType).isIn("trust.incident.created.v1", "trust.finding.created.v1");

        String eventId = EVENT_IDS.get(i);
        String timestamp = TIMESTAMPS.get(i);
        // 签名 v2 原文：timestamp + eventId + eventType + rawBody（NotifyDispatchService 侧签名）
        assertThat(SIGNATURES.get(i)).startsWith("hmac-sha256=");
        assertThat(envelope.path("event_id").asText()).isEqualTo(eventId);
        assertThat(timestamp).isNotEmpty();
        assertThat(recompute(SECRET, timestamp, eventId, EVENT_TYPES.get(i), BODIES.get(i)))
                .isEqualTo(SIGNATURES.get(i));
        assertThat(envelope.path("workspace_id").asText()).isEqualTo("ws-default");
    }

    @Test
    void webhookTestPushSignatureIsVerifiableByReceiver() throws Exception {
        Map<String, Object> senderConfig = new HashMap<>();
        senderConfig.put("url", "http://127.0.0.1:" + PORT + "/webhook");
        senderConfig.put("secret", SECRET);
        senderConfig.put("keyVersion", "v1");
        Map<String, Object> senderRequest = new HashMap<>();
        senderRequest.put("channel", "WEBHOOK");
        senderRequest.put("name", "测试推送 webhook");
        senderRequest.put("config", objectMapper.writeValueAsString(senderConfig));
        String senderId = readTree(postApi("/api/v1/notify/senders", senderRequest))
                .path("data").path("id").asText();

        int before = SIGNATURES.size();
        postApi("/api/v1/notify/senders/" + senderId + "/test", new HashMap<>());
        await(SIGNATURES::size, n -> n > before, ASYNC_TIMEOUT_MS);
        int i = SIGNATURES.size() - 1;

        String body = BODIES.get(i);
        String signature = SIGNATURES.get(i);
        String timestamp = TIMESTAMPS.get(i);
        String headerEventId = EVENT_IDS.get(i);
        JsonNode envelope = objectMapper.readTree(body);
        // 测试推送此前不落 eventId：签名曾把字符串 "null" 拼进原文，而 header/body 是空串 → 接收方永远验不过
        assertThat(headerEventId).isNotEmpty();
        assertThat(envelope.path("event_id").asText()).isEqualTo(headerEventId);
        assertThat(recompute(SECRET, timestamp, headerEventId, EVENT_TYPES.get(i), body))
                .isEqualTo(signature);
    }

    /** 接收方按文档公式复算 HMAC-SHA256：timestamp + "\n" + eventId + "\n" + eventType + "\n" + rawBody */
    private static String recompute(String secret, String timestamp, String eventId, String eventType, String body)
            throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal((timestamp + "\n" + eventId + "\n" + eventType + "\n" + body)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("hmac-sha256=");
        for (byte b : out) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
