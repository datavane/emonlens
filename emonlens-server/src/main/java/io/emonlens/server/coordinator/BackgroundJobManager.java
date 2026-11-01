package io.emonlens.server.coordinator;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.config.SliMetrics;
import io.emonlens.server.engine.metric.MetricScanService;
import io.emonlens.server.engine.retention.RetentionService;
import io.emonlens.server.engine.score.TrustScoreService;
import io.emonlens.server.engine.completion.TraceCompletionService;
import io.emonlens.server.engine.event.EventConsumeService;
import io.emonlens.server.ingest.ContentCaptureService;
import io.emonlens.server.ingest.IngestService;
import io.emonlens.server.ingest.SpanEnvelope;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.server.storage.entity.EventEntity;
import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.EventMapper;
import io.emonlens.server.storage.trace.TraceAssemblyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 后台执行体（进程内线程组，"api/worker 统一到 server"的落点）：
 * 1. span-batch-runner：微批写线程，消费 ingest 有界队列并推进 trace 组装；
 * 2. event-poll-runner：认领消费 et_event（无中心化，多实例天然分摊）；
 * 3. trace-sweep-runner：idle RUNNING trace 完成扫描。
 * 独立部署 worker 进程时将 emonlens.worker.enabled=false 于 api 实例关闭即可。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackgroundJobManager implements SmartLifecycle {

    private final IngestService ingestService;
    private final ContentCaptureService contentCaptureService;
    private final TraceAssemblyService traceAssemblyService;
    private final TraceCompletionService traceCompletionService;
    private final EventConsumeService eventConsumeService;
    private final MetricScanService metricScanService;
    private final TrustScoreService trustScoreService;
    private final EventMapper eventMapper;
    private final RetentionService retentionService;
    private final SliMetrics sliMetrics;
    private final EmonLensProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastReconcileAt = 0L;
    private Thread spanBatchRunner;
    private Thread eventPollRunner;
    private Thread traceSweepRunner;
    private Thread metricRunner;
    private Thread retentionRunner;
    private Thread scoreRunner;

    @Override
    public void start() {
        if (!properties.getWorker().isEnabled()) {
            log.info("worker threads disabled by config");
            // 注意：仍必须置 running=true。SmartLifecycle 契约下 Spring 只对 isRunning()
            // 为 true 的 bean 调 stop()；若此处不占位，api-only 实例停机时不会触发
            // flushIngestQueueOnShutdown()，而 ingest API 在该模式下照常接收——内存队列里
            // 未落盘的 span 会随进程退出全部丢失（实测：优雅停机后 span 仍未入库）。
            running.set(true);
            return;
        }
        if (running.compareAndSet(false, true)) {
            spanBatchRunner = new Thread(this::runSpanBatchLoop, "span-batch-runner");
            eventPollRunner = new Thread(this::runEventPollLoop, "event-poll-runner");
            traceSweepRunner = new Thread(this::runTraceSweepLoop, "trace-sweep-runner");
            spanBatchRunner.setDaemon(true);
            eventPollRunner.setDaemon(true);
            traceSweepRunner.setDaemon(true);
            metricRunner = new Thread(this::runMetricLoop, "metric-runner");
            retentionRunner = new Thread(this::runRetentionLoop, "retention-runner");
            metricRunner.setDaemon(true);
            retentionRunner.setDaemon(true);
            spanBatchRunner.start();
            eventPollRunner.start();
            traceSweepRunner.start();
            metricRunner.start();
            retentionRunner.start();
            scoreRunner = new Thread(this::runScoreLoop, "score-runner");
            scoreRunner.setDaemon(true);
            scoreRunner.start();
            log.info("worker threads started, instanceId={}", properties.getInstanceId());
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (spanBatchRunner != null) {
                spanBatchRunner.interrupt();
            }
            if (eventPollRunner != null) {
                eventPollRunner.interrupt();
            }
            if (traceSweepRunner != null) {
                traceSweepRunner.interrupt();
            }
            if (metricRunner != null) {
                metricRunner.interrupt();
            }
            if (retentionRunner != null) {
                retentionRunner.interrupt();
            }
            if (scoreRunner != null) {
                scoreRunner.interrupt();
            }
            // 先等写线程退出，避免停机刷盘与进行中的批量写并发；超时后继续刷盘兜底
            joinQuietly(spanBatchRunner, 5000);
            joinQuietly(eventPollRunner, 2000);
            joinQuietly(traceSweepRunner, 2000);
            joinQuietly(metricRunner, 2000);
            joinQuietly(retentionRunner, 2000);
            joinQuietly(scoreRunner, 2000);
            log.info("worker threads stopped");
        }
        // 两种模式都要刷盘：worker 启用时清写线程中断后的残留；api-only（worker 禁用）实例
        // 的队列同样可能非空——ingest API 在该模式下照常接收
        flushIngestQueueOnShutdown();
    }

    private void joinQuietly(Thread thread, long millis) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 优雅停机刷盘：写线程停止后，把 ingest 内存队列里未落盘的 span 尽量落库。
     * 落库失败的批次丢弃并告警（与运行时批次失败同策略）；最多尝试 3 轮，防坏数据死循环。
     */
    private void flushIngestQueueOnShutdown() {
        try {
            int flushed = 0;
            long deadline = System.currentTimeMillis() + 10_000;
            for (int round = 0; round < 100 && System.currentTimeMillis() < deadline; round++) {
                List<SpanEnvelope> batch = ingestService.drainBatch(0, properties.getIngest().getBatchSize());
                if (batch.isEmpty()) {
                    break;
                }
                List<SpanEventEntity> spans = new ArrayList<>(batch.size());
                for (SpanEnvelope envelope : batch) {
                    spans.add(envelope.getSpan());
                }
                List<TraceSummaryEntity> completed;
                try {
                    completed = traceAssemblyService.persistBatch(spans);
                } catch (Exception e) {
                    log.error("shutdown flush: batch persist failed, {} spans dropped", batch.size(), e);
                    continue;
                }
                flushed += batch.size();
                try {
                    traceCompletionService.onCompleted(completed);
                } catch (Exception e) {
                    log.error("shutdown flush: TRACE_COMPLETED enqueue failed, {} traces", completed.size(), e);
                }
                for (SpanEnvelope envelope : batch) {
                    if (envelope.getContents() != null && !envelope.getContents().isEmpty()) {
                        try {
                            contentCaptureService.capture(envelope.getSpan().getWorkspaceId(),
                                    envelope.getSpan().getTraceId(), envelope.getSpan().getSpanId(),
                                    envelope.getContents());
                        } catch (Exception e) {
                            log.warn("shutdown flush: content capture failed, trace={}, span={}",
                                    envelope.getSpan().getTraceId(), envelope.getSpan().getSpanId(), e);
                        }
                    }
                }
            }
            int remaining = ingestService.queueSize();
            if (remaining > 0) {
                log.warn("shutdown flush: {} spans still in queue could not be persisted", remaining);
            }
            log.info("shutdown flush: {} spans persisted from ingest queue", flushed);
        } catch (Exception e) {
            log.error("shutdown flush failed", e);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void runSpanBatchLoop() {
        long flushIntervalMs = properties.getIngest().getFlushIntervalMs();
        int batchSize = properties.getIngest().getBatchSize();
        // 已落库但 TRACE_COMPLETED 投递失败的 trace：留在下一轮重试，避免完成后检测/评分永不触发
        List<TraceSummaryEntity> pendingCompleted = new ArrayList<>();
        while (running.get()) {
            try {
                if (!pendingCompleted.isEmpty()) {
                    try {
                        traceCompletionService.onCompleted(pendingCompleted);
                        pendingCompleted.clear();
                    } catch (Exception e) {
                        log.error("trace completion event retry failed, {} traces pending", pendingCompleted.size(), e);
                    }
                }
                List<SpanEnvelope> batch = ingestService.drainBatch(flushIntervalMs, batchSize);
                if (batch.isEmpty()) {
                    continue;
                }
                List<SpanEventEntity> spans = new ArrayList<>(batch.size());
                for (SpanEnvelope envelope : batch) {
                    spans.add(envelope.getSpan());
                }
                List<TraceSummaryEntity> completed = traceAssemblyService.persistBatch(spans);
                try {
                    traceCompletionService.onCompleted(completed);
                } catch (Exception e) {
                    pendingCompleted.addAll(completed);
                    log.error("trace completion event enqueue failed, {} traces queued for retry", completed.size(), e);
                }
                for (SpanEnvelope envelope : batch) {
                    if (envelope.getContents() != null && !envelope.getContents().isEmpty()) {
                        try {
                            contentCaptureService.capture(envelope.getSpan().getWorkspaceId(),
                                    envelope.getSpan().getTraceId(), envelope.getSpan().getSpanId(),
                                    envelope.getContents());
                        } catch (Exception e) {
                            log.warn("content capture failed, trace={}, span={}",
                                    envelope.getSpan().getTraceId(), envelope.getSpan().getSpanId(), e);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 单批失败不终止写线程；批次内数据丢弃并计数，避免坏数据阻塞主链路
                log.error("span batch persist failed, batch dropped", e);
            }
        }
    }

    private void runEventPollLoop() {
        int pollBatchSize = properties.getEvent().getPollBatchSize();
        long pollIntervalMs = properties.getEvent().getPollIntervalMs();
        while (running.get()) {
            try {
                int processed = eventConsumeService.processPendingBatch(pollBatchSize);
                if (processed == 0) {
                    Thread.sleep(pollIntervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("event poll loop error", e);
                sleepQuietly(pollIntervalMs);
            }
        }
    }

    private void runTraceSweepLoop() {
        long intervalMs = properties.getSweeper().getIntervalMs();
        while (running.get()) {
            try {
                sliMetrics.addSweepCompleted(traceCompletionService.sweepIdle());
            } catch (Exception e) {
                log.error("trace sweep error", e);
            }
            maybeReconcileSummaries();
            sleepQuietly(intervalMs);
        }
    }

    /** summary 计数对账：周期兜底 StarRocks 等非事务数据面的计数漂移；0=关闭 */
    private void maybeReconcileSummaries() {
        long intervalMs = properties.getTrace().getReconcileIntervalMs();
        if (intervalMs <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReconcileAt < intervalMs) {
            return;
        }
        lastReconcileAt = now;
        try {
            int repaired = traceAssemblyService.reconcileCompleted(
                    LocalDateTime.now().minusHours(properties.getTrace().getReconcileRecentHours()),
                    properties.getTrace().getReconcileBatchSize());
            if (repaired > 0) {
                log.info("trace summary reconcile repaired {} traces", repaired);
            }
        } catch (Exception e) {
            log.error("trace summary reconcile failed", e);
        }
    }

    private void runMetricLoop() {
        long intervalMs = properties.getMetric().getScanIntervalMs();
        while (running.get()) {
            try {
                metricScanService.scanOnce();
                sliMetrics.refreshEventPending(eventPendingCount());
            } catch (Exception e) {
                log.error("metric scan error", e);
            }
            sleepQuietly(intervalMs);
        }
    }

    private void runRetentionLoop() {
        long intervalMs = properties.getRetention().getSweepIntervalMs();
        while (running.get()) {
            try {
                retentionService.purgeOnce();
            } catch (Exception e) {
                log.error("retention purge error", e);
            }
            sleepQuietly(intervalMs);
        }
    }

    private long eventPendingCount() {
        // 事件积压水位（SLI）：PENDING 事件计数
        Long count = eventMapper.selectCount(Wrappers.<EventEntity>lambdaQuery()
                .eq(EventEntity::getStatus, EventEntity.STATUS_PENDING));
        return count == null ? 0 : count;
    }

    private void runScoreLoop() {
        long intervalMs = properties.getScore().getRecomputeIntervalMs();
        // 启动后先做一次兜底重算：避免全新部署要等一个周期（默认 10 分钟）才有信任分快照
        sleepQuietly(5_000);
        while (running.get()) {
            if (!running.get()) {
                return;
            }
            try {
                trustScoreService.recomputeRecent(properties.getScore().getWindowHours());
            } catch (Exception e) {
                log.error("score recompute loop error", e);
            }
            sleepQuietly(intervalMs);
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
