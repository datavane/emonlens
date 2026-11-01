package io.emonlens.server.engine.score;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.engine.event.EventHandler;
import io.emonlens.server.storage.entity.EventEntity;
import io.emonlens.server.storage.entity.TrustScoreEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SCORE_CHANGED 事件处理：重算 agent 的窗口评分（与 WebhookPushHandler fan-out 并行消费）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreRecomputeHandler implements EventHandler {

    private final TrustScoreService trustScoreService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String eventType) {
        return "SCORE_CHANGED".equals(eventType);
    }

    @Override
    public void handle(EventEntity event) {
        String agentKey = extractAgentKey(event.getPayload(), event.getAgentKey());
        TrustScoreEntity snapshot = trustScoreService.recompute(event.getWorkspaceId(),
                agentKey, 24);
        log.debug("score recomputed on event, agent={}, score={}", agentKey, snapshot.getScore());
    }

    private String extractAgentKey(String payload, String fallback) {
        try {
            JsonNode node = objectMapper.readTree(payload == null ? "{}" : payload);
            return node.path("agentKey").asText(fallback);
        } catch (Exception e) {
            return fallback;
        }
    }
}
