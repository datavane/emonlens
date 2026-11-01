package io.emonlens.server.engine.completion;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.engine.event.EventType;
import io.emonlens.server.engine.event.EventService;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.trace.TraceAssemblyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * trace 完成编排：把"新完成的 trace"转为 TRACE_COMPLETED 事件。
 * 完成来源两路：ingest 微批写入时的瞬时完成、sweeper 对 idle RUNNING trace 的认领完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceCompletionService {

    private final TraceAssemblyService traceAssemblyService;
    private final EventService eventService;
    private final EmonLensProperties properties;
    private final ObjectMapper objectMapper;

    public void onCompleted(List<TraceSummaryEntity> completed) {
        for (TraceSummaryEntity summary : completed) {
            // traceId 来自上报数据，可能含引号等字符：必须序列化而不是字符串拼接
            String payload;
            try {
                payload = objectMapper.writeValueAsString(Map.of("traceId", summary.getTraceId()));
            } catch (Exception e) {
                payload = "{}";
            }
            eventService.enqueue(EventType.TRACE_COMPLETED, summary.getWorkspaceId(),
                    summary.getAgentKey(), payload);
            log.info("trace completed, traceId={}, agent={}", summary.getTraceId(), summary.getAgentKey());
        }
    }

    /** @return 本轮 sweeper 认领完成的 trace 数 */
    public int sweepIdle() {
        List<TraceSummaryEntity> claimed = traceAssemblyService.sweepIdleTraces(
                properties.getTrace().getIdleTimeoutMs(), properties.getSweeper().getBatchSize());
        onCompleted(claimed);
        if (!claimed.isEmpty()) {
            log.info("sweeper completed {} idle traces", claimed.size());
        }
        return claimed.size();
    }
}
