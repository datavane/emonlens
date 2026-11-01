package io.emonlens.server.engine.metric;

import lombok.Data;

/**
 * Metric Monitor DSL（et_monitor.spec_json，V1.1 §12.1）：
 * {metric: duration_ms|error_rate|steps_per_trace|trace_count,
 *  aggregation: avg|p95|max（仅 duration_ms 有效）,
 *  windowMinutes: 10, thresholdType: static|baseline_pct, thresholdValue: 5000,
 *  direction: above|below, baselineWindows: 6, consecutiveWindows: 3}
 */
@Data
public class MetricSpec {

    public static final String METRIC_DURATION = "duration_ms";
    public static final String METRIC_ERROR_RATE = "error_rate";
    public static final String METRIC_STEPS_PER_TRACE = "steps_per_trace";
    public static final String METRIC_TRACE_COUNT = "trace_count";

    private String metric;
    private String aggregation;
    private Integer windowMinutes;
    private String thresholdType;
    private Double thresholdValue;
    private String direction;
    private Integer baselineWindows;
    private Integer consecutiveWindows;
}
