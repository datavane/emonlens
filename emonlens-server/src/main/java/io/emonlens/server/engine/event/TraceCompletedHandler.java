package io.emonlens.server.engine.event;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.engine.detect.DetectionService;
import io.emonlens.server.engine.eval.EvaluationAutoRunService;
import io.emonlens.server.storage.entity.EventEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * TRACE_COMPLETED 事件处理器：解析 payload 并触发该 trace 的检测流水。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceCompletedHandler implements EventHandler {

    private final ObjectMapper objectMapper;
    private final DetectionService detectionService;
    private final EvaluationAutoRunService evaluationAutoRunService;

    @Override
    public boolean supports(String eventType) {
        return EventType.TRACE_COMPLETED.name().equals(eventType);
    }

    @Override
    public void handle(EventEntity event) {
        String traceId = extractTraceId(event.getPayload());
        if (traceId == null) {
            log.warn("TRACE_COMPLETED event without traceId, id={}", event.getId());
            return;
        }
        detectionService.processTrace(event.getWorkspaceId(), event.getAgentKey(), traceId);
        try {
            evaluationAutoRunService.runForTrace(event.getWorkspaceId(), traceId);
        } catch (Exception e) {
            // 自动评估失败不影响检测结果，事件层重试兜底
            log.error("auto evaluation failed, trace={}", traceId, e);
            throw e;
        }
    }

    private String extractTraceId(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.path("traceId").asText(null);
        } catch (Exception e) {
            log.warn("failed to parse event payload: {}", e.getMessage());
            return null;
        }
    }
}
