package io.emonlens.server.engine.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Metric 聚合基础语义：p95 分位与 spec 有效性。
 */
class MetricMonitorServiceTest {

    @Test
    void percentilePicksUpperBound() {
        List<Double> values = List.of(100.0, 200.0, 300.0, 400.0, 500.0);
        assertThat(MetricMonitorService.percentile(values, 0.95)).isEqualTo(500.0);
        assertThat(MetricMonitorService.percentile(values, 0.5)).isEqualTo(300.0);
    }

    @Test
    void invalidSpecRejected() {
        MetricSpec spec = new MetricSpec();
        spec.setMetric("duration_ms");
        assertThat(MetricMonitorService.valid(spec)).isFalse();
        spec.setWindowMinutes(10);
        spec.setThresholdType("static");
        spec.setThresholdValue(1000.0);
        assertThat(MetricMonitorService.valid(spec)).isFalse();
        spec.setConsecutiveWindows(2);
        assertThat(MetricMonitorService.valid(spec)).isTrue();
    }
}
