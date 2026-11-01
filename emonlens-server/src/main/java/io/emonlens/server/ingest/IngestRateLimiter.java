package io.emonlens.server.ingest;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.emonlens.server.config.EmonLensProperties;
import lombok.RequiredArgsConstructor;

/**
 * 每 workspace 的 ingest 令牌桶限流（实例内存态，Redis 分布式限流后续替换）：
 * 防止单个 Agent 异常刷量挤占其他 workspace 的队列容量。
 * 桶容量 = 每分钟额度（允许一分钟额度的突发），按秒线性回填；
 * emonlens.ingest.rate-limit-spans-per-minute=0 关闭。
 */
@Component
@RequiredArgsConstructor
public class IngestRateLimiter {

    private final EmonLensProperties properties;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong limitedTotal =
            new java.util.concurrent.atomic.AtomicLong();

    /** 尝试消费 spans 个令牌；不足时返回 false 并计入限流总数 */
    public boolean tryConsume(String workspaceId, int spans) {
        long perMinute = properties.getIngest().getRateLimitSpansPerMinute();
        if (perMinute <= 0) {
            return true;
        }
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(workspaceId, k -> new Bucket(perMinute));
        synchronized (bucket) {
            // elapsed 可能为负（新桶的 lastNanos 晚于本次调用的 now 采样），必须钳制为 0，
            // 否则满额度批次（spans == perMinute）会被误拒
            double elapsedSec = Math.max(0, (now - bucket.lastNanos) / 1e9);
            bucket.tokens = Math.min(perMinute, bucket.tokens + elapsedSec * (perMinute / 60.0));
            bucket.lastNanos = now;
            if (bucket.tokens >= spans) {
                bucket.tokens -= spans;
                return true;
            }
        }
        limitedTotal.incrementAndGet();
        return false;
    }

    public long getLimitedTotal() {
        return limitedTotal.get();
    }

    public int trackedWorkspaces() {
        return buckets.size();
    }

    /** 单批上报即使超出整桶额度也按整桶拒绝，不做部分放行（客户端整批退避重试） */
    private static final class Bucket {
        private double tokens;
        private long lastNanos = System.nanoTime();

        private Bucket(double initial) {
            this.tokens = initial;
        }
    }
}
