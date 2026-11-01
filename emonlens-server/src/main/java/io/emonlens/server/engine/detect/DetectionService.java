package io.emonlens.server.engine.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.engine.eval.EvaluationMonitorChecker;
import io.emonlens.server.engine.finding.FindingService;
import io.emonlens.server.engine.monitor.MonitorService;
import io.emonlens.server.engine.monitor.MonitorSpec;
import io.emonlens.server.storage.entity.FindingEntity;
import io.emonlens.server.storage.entity.MonitorEntity;
import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.trace.TraceAssemblyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 检测流水：trace 完成 -> 加载 spans -> 执行该 agent 的启用 Monitor（Validation/Trajectory）
 * + 内置 Behavior Loop 检测 -> Finding（fingerprint 归并 Incident）。
 * 单个 Monitor 失败不影响其他检测。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionService {

    private final TraceAssemblyService traceAssemblyService;
    private final MonitorService monitorService;
    private final BehaviorLoopDetector behaviorLoopDetector;
    private final EvaluationMonitorChecker evaluationMonitorChecker;
    private final FindingService findingService;
    private final ObjectMapper objectMapper;

    public void processTrace(String workspaceId, String agentKey, String traceId) {
        TraceSummaryEntity summary = traceAssemblyService.loadSummary(workspaceId, traceId);
        if (summary == null) {
            log.warn("trace summary not found for detection, traceId={}", traceId);
            return;
        }
        List<SpanEventEntity> spans = traceAssemblyService.loadSpans(workspaceId, traceId);
        List<MonitorEntity> monitors = monitorService.listEnabled(workspaceId, agentKey);
        for (MonitorEntity monitor : monitors) {
            try {
                if (MonitorEntity.KIND_EVALUATION.equals(monitor.getKind())) {
                    Violation violation = evaluationMonitorChecker.check(
                            monitor, summary.getWorkspaceId(), summary.getAgentKey(), traceId);
                    if (violation != null) {
                        findingService.record(evaluationMonitorChecker.toFinding(summary, monitor, violation));
                    }
                    continue;
                }
                MonitorSpec spec = monitorService.parseSpec(monitor);
                if (spec == null) {
                    continue;
                }
                List<Violation> violations = MonitorEntity.KIND_VALIDATION.equals(monitor.getKind())
                        ? ValidationRule.evaluate(spans, spec)
                        : TrajectoryMatcher.evaluate(spans, spec);
                for (Violation violation : violations) {
                    findingService.record(buildMonitorFinding(summary, monitor, violation));
                }
            } catch (Exception e) {
                log.error("monitor {} failed on trace {}", monitor.getId(), traceId, e);
            }
        }
        try {
            for (Violation violation : behaviorLoopDetector.detect(spans)) {
                findingService.record(buildBehaviorFinding(summary, violation));
            }
        } catch (Exception e) {
            log.error("behavior loop detection failed on trace {}", traceId, e);
        }
    }

    private FindingEntity buildMonitorFinding(TraceSummaryEntity summary, MonitorEntity monitor,
                                              Violation violation) {
        FindingEntity finding = new FindingEntity();
        finding.setWorkspaceId(summary.getWorkspaceId());
        finding.setAgentKey(summary.getAgentKey());
        finding.setAgentVersion(summary.getAgentVersion());
        finding.setTraceId(summary.getTraceId());
        finding.setSpanId(violation.getSpanId());
        finding.setDimension(monitor.getDimension());
        finding.setType(MonitorEntity.KIND_VALIDATION.equals(monitor.getKind())
                ? FindingEntity.TYPE_MONITOR_VALIDATION : FindingEntity.TYPE_MONITOR_TRAJECTORY);
        finding.setSeverity(monitor.getSeverity());
        finding.setDetectorId(monitor.getMonitorKey());
        finding.setMessage(violation.getMessage());
        finding.setEvidence(toJson(violation.getEvidence()));
        return finding;
    }

    private FindingEntity buildBehaviorFinding(TraceSummaryEntity summary, Violation violation) {
        FindingEntity finding = new FindingEntity();
        finding.setWorkspaceId(summary.getWorkspaceId());
        finding.setAgentKey(summary.getAgentKey());
        finding.setAgentVersion(summary.getAgentVersion());
        finding.setTraceId(summary.getTraceId());
        finding.setSpanId(violation.getSpanId());
        finding.setDimension("BEHAVIOR");
        finding.setType(FindingEntity.TYPE_BEHAVIOR_LOOP);
        finding.setSeverity("HIGH");
        finding.setDetectorId("behavior-loop");
        finding.setMessage(violation.getMessage());
        finding.setEvidence(toJson(violation.getEvidence()));
        return finding;
    }

    private String toJson(Map<String, Object> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            log.warn("failed to serialize evidence: {}", e.getMessage());
            return null;
        }
    }
}
