package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

/**
 * Webhook 回推端到端：Finding 产出 → FINDING_CREATED 事件 → HMAC 签名 POST →
 * 桩服务器收到 envelope 且签名可验证 → et_push_record SUCCESS。
 * 桩服务器用 JDK 内置 HttpServer（静态初始化，固定端口 18309）。
 */
@SpringBootTest(properties = {
        "emonlens.push.enabled=true",
        "emonlens.push.base-url=http://127.0.0.1:18309",
        "emonlens.push.secret=test-secret",
        "spring.datasource.url=jdbc:h2:mem:emonlens-push;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class WebhookPushFlowTest extends AbstractIntegrationTest {

    private static final int PORT = 18309;
    private static final String SECRET = "test-secret";
    private static final List<byte[]> BODIES = new CopyOnWriteArrayList<>();
    private static final List<String> SIGNATURES = new CopyOnWriteArrayList<>();
    private static HttpServer server;

    static {
        try {
            server = HttpServer.create(new java.net.InetSocketAddress(PORT), 0);
            server.createContext("/api/v1/trust-adapter/events", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                BODIES.add(body);
                SIGNATURES.add(exchange.getRequestHeaders().getFirst("X-Em-Signature"));
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write("ok".getBytes());
                exchange.close();
            });
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void findingEventIsPushedWithValidSignature() throws Exception {
        // 触发一条 Finding（未批准模型）
        Map<String, Object> spec = new HashMap<>();
        spec.put("kind", "VALIDATION");
        spec.put("spanType", "llm");
        spec.put("where", "model_name != null && model_name != 'approved-model-a'");
        Map<String, Object> monitorRequest = new HashMap<>();
        monitorRequest.put("agentKey", "push-agent");
        monitorRequest.put("monitorKey", "push-model-list");
        monitorRequest.put("kind", "VALIDATION");
        monitorRequest.put("spec", spec);
        postApi("/api/v1/monitors", monitorRequest);

        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span("push-agent", "push-trace-1", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        spans.add(span("push-agent", "push-trace-1", "llm", "root", "llm", "call_model", null,
                "gpt-unapproved", 2, 4));
        post("/api/v1/ingest/events", spans);

        // 等待桩服务器收到回推
        await(BODIES::size, n -> n >= 1, ASYNC_TIMEOUT_MS);

        // envelope 结构 + HMAC 签名验证
        JsonNode envelope = objectMapper.readTree(BODIES.get(0));
        assertThat(envelope.path("event_type").asText()).isEqualTo("trust.finding.created.v1");
        assertThat(envelope.path("workspace_id").asText()).isEqualTo("ws-default");
        assertThat(envelope.path("producer").asText()).isEqualTo("emonlens-engine");
        assertThat(envelope.path("payload").path("traceId").asText()).isEqualTo("push-trace-1");

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(), "HmacSHA256"));
        byte[] digest = mac.doFinal(BODIES.get(0));
        StringBuilder expected = new StringBuilder("hmac-sha256=");
        for (byte b : digest) {
            expected.append(String.format("%02x", b));
        }
        assertThat(SIGNATURES.get(0)).isEqualTo(expected.toString());
    }

    private JsonNode readTreeSafely(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
