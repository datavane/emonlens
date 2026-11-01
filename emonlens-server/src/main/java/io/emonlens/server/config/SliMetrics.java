package io.emonlens.server.config;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import io.emonlens.server.ingest.IngestService;

import lombok.RequiredArgsConstructor;

/**
 * 平台自观测 SLI（设计文档 §5.3）：micrometer gauges，前缀 emonlens_。
 * 事件积压由 metric-runner 周期刷新（避免每次读库打爆 gauge）。
 */
@Component
@RequiredArgsConstructor
public class SliMetrics implements MeterBinder {

    private final IngestService ingestService;
    private final EmonLensProperties properties;

    private final AtomicLong eventPending = new AtomicLong();
    private final AtomicLong sweepCompleted = new AtomicLong();

    @Override
    public void bindTo(MeterRegistry registry) {
        registry.gauge("emonlens_ingest_queue_size", ingestService, s -> s.queueSize());
        registry.gauge("emonlens_ingest_dropped_total", ingestService, s -> s.getDroppedTotal());
        registry.gauge("emonlens_event_pending", eventPending);
        registry.gauge("emonlens_sweep_completed_total", sweepCompleted);
    }

    public void refreshEventPending(long pending) {
        eventPending.set(pending);
    }

    public void addSweepCompleted(long delta) {
        sweepCompleted.addAndGet(delta);
    }
}
