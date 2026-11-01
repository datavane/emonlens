package io.emonlens.server.engine.metric;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.FindingEntity;
import io.emonlens.server.storage.entity.MonitorEntity;
import io.emonlens.server.storage.entity.MonitorStateEntity;
import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.MonitorStateMapper;
import io.emonlens.server.storage.span.SpanEventStore;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.trace.TraceAssemblyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Metric Monitor：时间窗聚合 + 静态/基线阈值 + 连续窗口确认（V1.1 §12.1）。
 * 状态存 et_monitor_state（每窗口一行，consecutive_hits 跨窗口累计）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricMonitorService {

    private final TraceSummaryMapper traceSummaryMapper;
    private final SpanEventStore spanStore;
    private final MonitorStateMapper monitorStateMapper;
    private final TraceAssemblyService traceAssemblyService;
    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;

    /** 评估单个 Metric Monitor 当前窗口；命中连续阈值时产出 Finding（可能为 null） */
    public FindingEntity evaluate(MonitorEntity monitor, LocalDateTime now) {
        MetricSpec spec = parseSpec(monitor);
        if (spec == null || !valid(spec)) {
            return null;
        }
        int windowMinutes = spec.getWindowMinutes();
        LocalDateTime windowStart = now.minusMinutes(windowMinutes);
        Double actual = computeMetric(monitor, spec, windowStart, now);
        if (actual == null) {
            return null;
        }
        boolean violated;
        if ("baseline_pct".equals(spec.getThresholdType())) {
            Double baseline = computeBaseline(monitor, spec, windowStart);
            if (baseline == null || baseline == 0) {
                violated = false;
            } else {
                double deviationPct = (actual - baseline) / baseline * 100;
                violated = "below".equals(spec.getDirection()) ? deviationPct < -spec.getThresholdValue()
                        : deviationPct > spec.getThresholdValue();
            }
        } else {
            violated = "below".equals(spec.getDirection()) ? actual < spec.getThresholdValue()
                    : actual > spec.getThresholdValue();
        }

        int hits = recordWindowHit(monitor, spec, windowStart, now, actual, violated);
        if (!violated || hits < spec.getConsecutiveWindows()) {
            return null;
        }
        Map<String, Object> evidence = new java.util.HashMap<>();
        evidence.put("metric", spec.getMetric());
        evidence.put("aggregation", spec.getAggregation());
        evidence.put("actual", actual);
        evidence.put("threshold_type", spec.getThresholdType());
        evidence.put("threshold_value", spec.getThresholdValue());
        evidence.put("direction", spec.getDirection());
        evidence.put("consecutive_windows", hits);
        return buildFinding(monitor, "metric monitor '" + monitor.getMonitorKey() + "': "
                + spec.getMetric() + "=" + round(actual) + " "
                + ("below".equals(spec.getDirection()) ? "<" : ">") + " " + spec.getThresholdValue()
                + " for " + hits + " windows", evidence);
    }

    /** 窗口命中状态推进；返回累计连续命中数 */
    private int recordWindowHit(MonitorEntity monitor, MetricSpec spec, LocalDateTime windowStart,
                                LocalDateTime now, Double actual, boolean violated) {
        // 前一窗口状态（时间倒序最近一行）
        MonitorStateEntity prev = monitorStateMapper.selectOne(Wrappers.<MonitorStateEntity>lambdaQuery()
                .eq(MonitorStateEntity::getMonitorId, monitor.getId())
                .lt(MonitorStateEntity::getWindowStart, windowStart)
                .orderByDesc(MonitorStateEntity::getWindowStart)
                .last("LIMIT 1"));
        int hits = violated ? (prev == null || prev.getConsecutiveHits() == null ? 1 : prev.getConsecutiveHits() + 1) : 0;
        MonitorStateEntity state = new MonitorStateEntity();
        state.setMonitorId(monitor.getId());
        state.setWindowStart(windowStart);
        state.setConsecutiveHits(hits);
        state.setPrevValue(BigDecimal.valueOf(actual).setScale(4, RoundingMode.HALF_UP));
        state.setLastEvalAt(now);
        try {
            monitorStateMapper.insert(state);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 同窗口重复评估（多实例/重跑）：保留先到记录
        }
        return hits;
    }

    Double computeMetric(MonitorEntity monitor, MetricSpec spec, LocalDateTime windowStart, LocalDateTime now) {
        List<TraceSummaryEntity> traces = traceSummaryMapper.selectList(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, monitor.getWorkspaceId())
                .eq(TraceSummaryEntity::getAgentKey, monitor.getAgentKey())
                .ge(TraceSummaryEntity::getStartTime, windowStart)
                .lt(TraceSummaryEntity::getStartTime, now)
                .last("LIMIT " + properties.getMetric().getSampleLimit()));
        if (MetricSpec.METRIC_TRACE_COUNT.equals(spec.getMetric())) {
            return (double) traces.size();
        }
        if (traces.isEmpty()) {
            return null;
        }
        if (MetricSpec.METRIC_ERROR_RATE.equals(spec.getMetric())) {
            long errors = traces.stream().filter(t -> t.getErrorCount() != null && t.getErrorCount() > 0).count();
            return (double) errors / traces.size();
        }
        if (MetricSpec.METRIC_STEPS_PER_TRACE.equals(spec.getMetric())) {
            long toolSpans = spanStore.search(SpanEventStore.SpanQuery.builder()
                    .workspaceId(monitor.getWorkspaceId())
                    .agentKey(monitor.getAgentKey())
                    .startTimeFrom(windowStart)
                    .startTimeTo(now)
                    .spanTypes(io.emonlens.server.engine.detect.TrajectorySteps.stepSpanTypes())
                    .countOnly(true)
                    .build()).getTotal();
            return (double) toolSpans / traces.size();
        }
        // duration_ms 聚合
        List<Double> durations = new ArrayList<>();
        for (TraceSummaryEntity trace : traces) {
            if (trace.getDurationMs() != null) {
                durations.add(trace.getDurationMs().doubleValue());
            }
        }
        if (durations.isEmpty()) {
            return null;
        }
        String aggregation = spec.getAggregation() == null ? "avg" : spec.getAggregation();
        if ("p95".equals(aggregation)) {
            return percentile(durations, 0.95);
        }
        if ("max".equals(aggregation)) {
            return durations.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        }
        return durations.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** 基线：当前窗口之前 baselineWindows 个窗口的同指标均值 */
    Double computeBaseline(MonitorEntity monitor, MetricSpec spec, LocalDateTime currentWindowStart) {
        int baselineWindows = spec.getBaselineWindows() == null
                ? properties.getMetric().getBaselineWindows() : spec.getBaselineWindows();
        List<Double> values = new ArrayList<>();
        LocalDateTime windowEnd = currentWindowStart;
        for (int i = 0; i < baselineWindows; i++) {
            LocalDateTime start = windowEnd.minusMinutes(spec.getWindowMinutes());
            Double v = computeMetric(monitor, spec, start, windowEnd);
            if (v != null) {
                values.add(v);
            }
            windowEnd = start;
        }
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    static double percentile(List<Double> values, double p) {
        List<Double> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    static boolean valid(MetricSpec spec) {
        return spec.getMetric() != null && !spec.getMetric().isEmpty()
                && spec.getWindowMinutes() != null && spec.getWindowMinutes() > 0
                && spec.getThresholdType() != null && spec.getThresholdValue() != null
                && spec.getConsecutiveWindows() != null && spec.getConsecutiveWindows() > 0;
    }

    private FindingEntity buildFinding(MonitorEntity monitor, String message, java.util.Map<String, Object> evidence) {
        FindingEntity finding = new FindingEntity();
        finding.setWorkspaceId(monitor.getWorkspaceId());
        finding.setAgentKey(monitor.getAgentKey());
        finding.setDimension("PERFORMANCE");
        finding.setType("MONITOR_METRIC");
        finding.setSeverity(monitor.getSeverity());
        finding.setDetectorId(monitor.getMonitorKey());
        finding.setMessage(message);
        try {
            finding.setEvidence(objectMapper.writeValueAsString(evidence));
        } catch (Exception e) {
            finding.setEvidence(null);
        }
        return finding;
    }

    MetricSpec parseSpec(MonitorEntity monitor) {
        try {
            return objectMapper.readValue(monitor.getSpecJson(), MetricSpec.class);
        } catch (Exception e) {
            log.warn("metric monitor spec unparseable, id={}", monitor.getId());
            return null;
        }
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
