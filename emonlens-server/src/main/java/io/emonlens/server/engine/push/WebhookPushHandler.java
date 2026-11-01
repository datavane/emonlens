package io.emonlens.server.engine.push;

import java.util.Set;

import org.springframework.stereotype.Component;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.engine.event.EventHandler;
import io.emonlens.server.storage.entity.EventEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Webhook 回推 handler：FINDING_CREATED / INCIDENT_CHANGED / EVAL_RESULT_READY / SCORE_CHANGED。
 * push 未启用时直接完成（引擎自治，不阻塞事件链）；投递失败抛出走事件退避重试 -> DEAD。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookPushHandler implements EventHandler {

    private static final Set<String> PUSH_TYPES = Set.of(
            "FINDING_CREATED", "INCIDENT_CREATED", "INCIDENT_ESCALATED", "INCIDENT_STATUS_CHANGED", "EVAL_RESULT_READY", "SCORE_CHANGED");

    private final WebhookPushService webhookPushService;
    private final EmonLensProperties properties;

    @Override
    public boolean supports(String eventType) {
        return PUSH_TYPES.contains(eventType);
    }

    @Override
    public void handle(EventEntity event) {
        if (!properties.getPush().isEnabled()
                || properties.getPush().getBaseUrl() == null
                || properties.getPush().getBaseUrl().isEmpty()) {
            return;
        }
        boolean delivered = webhookPushService.deliver(event);
        if (!delivered) {
            // 触发 EventConsumeService 的退避重试（1m/2m/3m 后重投，超限 DEAD）
            throw new IllegalStateException("webhook delivery failed, event=" + event.getId());
        }
    }
}
