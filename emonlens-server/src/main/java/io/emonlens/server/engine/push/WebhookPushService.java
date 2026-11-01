package io.emonlens.server.engine.push;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.EventEntity;
import io.emonlens.server.storage.entity.PushRecordEntity;
import io.emonlens.server.storage.mapper.PushRecordMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Webhook 回推（设计文档 §3.4 ③）：事件 envelope + HMAC-SHA256 签名 + X-Em-Event-Id 幂等头。
 * 失败抛 PushFailedException 走既有事件退避重试 -> DEAD；每次尝试记录 et_push_record。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookPushService {

    public static final String SIGNATURE_HEADER = "X-Em-Signature";
    public static final String EVENT_ID_HEADER = "X-Em-Event-Id";
    public static final String EVENT_TYPE_HEADER = "X-Em-Event-Type";
    public static final String ADAPTER_PATH = "/api/v1/trust-adapter/events";

    private final EmonLensProperties properties;
    private final PushRecordMapper pushRecordMapper;
    private final ObjectMapper objectMapper;

    /** @return true 投递成功；false 下游拒绝（触发事件层重试） */
    public boolean deliver(EventEntity event) {
        EmonLensProperties.Push push = properties.getPush();
        String body = buildEnvelope(event);
        int attempts = 0;
        String lastStatus;
        String lastResponse;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    push.getBaseUrl().replaceAll("/$", "") + ADAPTER_PATH).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(push.getTimeoutMs());
            conn.setReadTimeout(push.getTimeoutMs());
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty(EVENT_ID_HEADER, event.getId());
            conn.setRequestProperty(EVENT_TYPE_HEADER, event.getEventType());
            conn.setRequestProperty(SIGNATURE_HEADER, "hmac-sha256=" + hmac(body));
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            attempts++;
            if (code >= 200 && code < 300) {
                lastStatus = PushRecordEntity.STATUS_SUCCESS;
                lastResponse = "http " + code;
                record(event, body, attempts, lastStatus, lastResponse);
                return true;
            }
            lastStatus = PushRecordEntity.STATUS_FAILED;
            lastResponse = "http " + code;
        } catch (IOException e) {
            attempts++;
            lastStatus = PushRecordEntity.STATUS_FAILED;
            lastResponse = "io: " + e.getMessage();
        }
        record(event, body, attempts, lastStatus, lastResponse);
        log.warn("webhook push failed, event={}, type={}, status={}", event.getId(), event.getEventType(), lastStatus);
        return false;
    }

    /** envelope 与 V1.1 §9 / 设计文档 §3.4 一致 */
    String buildEnvelope(EventEntity event) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("event_id", event.getId());
        envelope.put("event_type", "trust." + event.getEventType().toLowerCase().replace('_', '.') + ".v1");
        envelope.put("workspace_id", event.getWorkspaceId());
        envelope.put("occurred_at", LocalDateTime.now() + "");
        envelope.put("producer", "emonlens-engine");
        envelope.put("schema_version", 1);
        envelope.put("correlation_id", event.getAgentKey());
        envelope.put("payload", parsePayload(event.getPayload()));
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("envelope serialize failed", e);
        }
    }

    private Map<String, Object> parsePayload(String payload) {
        try {
            return objectMapper.readValue(payload == null ? "{}" : payload, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getPush().getSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] bytes = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }

    private void record(EventEntity event, String body, int attempts, String status, String response) {
        PushRecordEntity record = new PushRecordEntity();
        record.setEventId(event.getId());
        record.setEventType(event.getEventType());
        record.setTargetUrl(properties.getPush().getBaseUrl() + ADAPTER_PATH);
        record.setAttempts(attempts);
        record.setLastStatus(status);
        record.setLastResponse(response == null || response.length() <= 512
                ? response : response.substring(0, 512));
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        pushRecordMapper.insert(record);
    }
}
