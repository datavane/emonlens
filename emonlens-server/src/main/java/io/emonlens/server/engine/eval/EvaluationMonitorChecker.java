package io.emonlens.server.engine.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.engine.detect.Violation;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.FindingEntity;
import io.emonlens.server.storage.entity.MonitorEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.FindingMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Evaluation Monitor：对 eval_result 序列做阈值判定（V1.1 §12.4）。
 * spec: {evaluatorKey, metric: pass_rate|avg_score, op: "<"|">", value, windowCount}
 * 红线：EVAL_UNAVAILABLE / N/A 属"未知"，既不算合格也不算失败——pass_rate 分母只含已判定（PASS/FAIL）结果；
 * 窗口内全部降级时不判定（返回 null），避免降级拖垮 pass_rate 制造误报。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluationMonitorChecker {

    private final EvalResultMapper evalResultMapper;
    private final ObjectMapper objectMapper;

    /** @return 违规描述；无违规返回 null */
    public Violation check(MonitorEntity monitor, String workspaceId, String agentKey,
                           String traceId) {
        Map<String, Object> spec;
        try {
            spec = objectMapper.readValue(monitor.getSpecJson(), Map.class);
        } catch (Exception e) {
            log.warn("evaluation monitor spec unparseable, id={}", monitor.getId());
            return null;
        }
        String evaluatorKey = String.valueOf(spec.getOrDefault("evaluatorKey", ""));
        if (evaluatorKey.isEmpty()) {
            return null;
        }
        String metric = String.valueOf(spec.getOrDefault("metric", "pass_rate"));
        String op = String.valueOf(spec.getOrDefault("op", "<"));
        double threshold = spec.get("value") instanceof Number
                ? ((Number) spec.get("value")).doubleValue() : Double.NaN;
        int windowCount = spec.get("windowCount") instanceof Number
                ? ((Number) spec.get("windowCount")).intValue() : 20;
        if (Double.isNaN(threshold)) {
            return null;
        }
        List<EvalResultEntity> results = evalResultMapper.selectList(Wrappers.<EvalResultEntity>lambdaQuery()
                .eq(EvalResultEntity::getWorkspaceId, workspaceId)
                .eq(EvalResultEntity::getAgentKey, agentKey)
                .eq(EvalResultEntity::getEvaluatorKey, evaluatorKey)
                .orderByDesc(EvalResultEntity::getCreatedAt)
                .last("LIMIT " + Math.max(1, windowCount)));
        if (results.isEmpty()) {
            return null;
        }
        double actual;
        if ("pass_rate".equals(metric)) {
            List<EvalResultEntity> judged = results.stream()
                    .filter(r -> EvaluatorEntity.LABEL_PASS.equals(r.getLabel())
                            || EvaluatorEntity.LABEL_FAIL.equals(r.getLabel()))
                    .collect(java.util.stream.Collectors.toList());
            if (judged.isEmpty()) {
                return null;
            }
            long pass = judged.stream()
                    .filter(r -> EvaluatorEntity.LABEL_PASS.equals(r.getLabel())).count();
            actual = (double) pass / judged.size();
        } else if ("avg_score".equals(metric)) {
            actual = results.stream()
                    .filter(r -> r.getScore() != null && !EvaluatorEntity.LABEL_EVAL_UNAVAILABLE.equals(r.getLabel()))
                    .mapToDouble(r -> r.getScore().doubleValue()).average().orElse(0);
        } else {
            return null;
        }
        boolean violated = "<".equals(op) ? actual < threshold : actual > threshold;
        if (!violated) {
            return null;
        }
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("evaluator_key", evaluatorKey);
        evidence.put("metric", metric);
        evidence.put("op", op);
        evidence.put("threshold", threshold);
        evidence.put("actual", BigDecimal.valueOf(actual).setScale(4, RoundingMode.HALF_UP).doubleValue());
        evidence.put("sample_count", results.size());
        evidence.put("trace_id", traceId);
        return new Violation("evaluation monitor '" + monitor.getMonitorKey() + "': " + metric
                + "=" + actual + " " + op + " " + threshold, null, evidence);
    }

    /** 由 DetectionService 调用：违规转 Finding */
    public FindingEntity toFinding(TraceSummaryEntity summary, MonitorEntity monitor, Violation violation) {
        FindingEntity finding = new FindingEntity();
        finding.setWorkspaceId(summary.getWorkspaceId());
        finding.setWorkspaceId(summary.getWorkspaceId());
        finding.setAgentKey(summary.getAgentKey());
        finding.setAgentVersion(summary.getAgentVersion());
        finding.setTraceId(summary.getTraceId());
        finding.setSpanId(violation.getSpanId());
        finding.setDimension(monitor.getDimension());
        finding.setType("MONITOR_EVALUATION");
        finding.setSeverity(monitor.getSeverity());
        finding.setDetectorId(monitor.getMonitorKey());
        finding.setMessage(violation.getMessage());
        try {
            finding.setEvidence(objectMapper.writeValueAsString(violation.getEvidence()));
        } catch (Exception e) {
            finding.setEvidence(null);
        }
        return finding;
    }
}
