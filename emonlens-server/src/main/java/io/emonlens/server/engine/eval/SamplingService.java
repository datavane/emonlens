package io.emonlens.server.engine.eval;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.config.SysSettingService;
import io.emonlens.server.storage.entity.TraceSummaryEntity;

/**
 * 评估采样决策（确定性规则，V1.1 §13.3）：
 * 强制采样：trace 存在 error；其余按 default_rate 随机；
 * 小时预算：超过 max_per_hour 的自动评估直接拒绝。
 */
@Service
public class SamplingService {

    private final EmonLensProperties properties;
    private final AtomicLong hourCounter = new AtomicLong();
    private volatile long hourBucket = 0;

    private final SysSettingService sysSettingService;

    public SamplingService(EmonLensProperties properties, SysSettingService sysSettingService) {
        this.properties = properties;
        this.sysSettingService = sysSettingService;
    }

    private double effectiveRate() {
        SysSettingService.EvalSampling db = sysSettingService.getEvalSampling();
        if (db != null && db.getDefaultRate() != null) {
            return db.getDefaultRate();
        }
        return properties.getEval().getDefaultRate();
    }

    private int effectiveMaxPerHour() {
        SysSettingService.EvalSampling db = sysSettingService.getEvalSampling();
        if (db != null && db.getMaxPerHour() != null) {
            return db.getMaxPerHour();
        }
        return properties.getEval().getMaxPerHour();
    }

    public boolean shouldAutoEvaluate(TraceSummaryEntity summary) {
        if (effectiveRate() <= 0) {
            return false;
        }
        // 强制采样：错误 trace 全量评估
        if (summary.getErrorCount() != null && summary.getErrorCount() > 0) {
            return reserveBudget();
        }
        if (ThreadLocalRandom.current().nextDouble() >= effectiveRate()) {
            return false;
        }
        return reserveBudget();
    }

    private synchronized boolean reserveBudget() {
        long bucket = System.currentTimeMillis() / 3600000L;
        if (bucket != hourBucket) {
            hourBucket = bucket;
            hourCounter.set(0);
        }
        if (hourCounter.incrementAndGet() > effectiveMaxPerHour()) {
            return false;
        }
        return true;
    }
}
