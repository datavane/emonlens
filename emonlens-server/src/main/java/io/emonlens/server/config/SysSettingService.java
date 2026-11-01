package io.emonlens.server.config;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.server.storage.entity.SysSettingEntity;
import io.emonlens.server.storage.mapper.SysSettingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 系统运行时设置（et_sys_setting，KV）。目前承载 eval.llm（评估/Playground 共用的 LLM 端点）。
 * 读取带 10s 进程内缓存，写入即失效缓存；DB 值优先于 application.yml。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysSettingService {

    public static final String KEY_EVAL_LLM = "eval.llm";
    public static final String KEY_EVAL_SAMPLING = "eval.sampling";
    public static final String KEY_RETENTION = "retention";

    private final SysSettingMapper sysSettingMapper;
    private final ObjectMapper objectMapper;

    private volatile long cacheAt = 0;
    private volatile boolean evalLlmLoaded = false;
    private volatile EvalLlm cachedEvalLlm;
    private volatile long samplingCacheAt = 0;
    private volatile EvalSampling samplingCache;
    private volatile long retentionCacheAt = 0;
    private volatile Retention retentionCache;

    /** 评估/Playground 共用的 LLM 端点配置；未配置时返回 null（调用方回退 yml）。
     *  负结果同样进入 10s 缓存：纯 yml 部署下否则每次 LLM 调用都会打一次设置表。 */
    public EvalLlm getEvalLlm() {
        long now = System.currentTimeMillis();
        if (evalLlmLoaded && now - cacheAt < 10_000) {
            return cachedEvalLlm;
        }
        SysSettingEntity row = sysSettingMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysSettingEntity>lambdaQuery()
                        .eq(SysSettingEntity::getWorkspaceId, SysSettingEntity.WORKSPACE_GLOBAL)
                        .eq(SysSettingEntity::getSettingKey, KEY_EVAL_LLM)
                        .last("LIMIT 1"));
        cachedEvalLlm = parse(row);
        evalLlmLoaded = true;
        cacheAt = now;
        return cachedEvalLlm;
    }

    public synchronized void saveEvalLlm(EvalLlm settings) {
        String json;
        try {
            json = objectMapper.writeValueAsString(settings);
        } catch (Exception e) {
            throw new EmonLensException(io.emonlens.common.ErrorCode.BAD_REQUEST, "设置序列化失败");
        }
        SysSettingEntity row = sysSettingMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysSettingEntity>lambdaQuery()
                        .eq(SysSettingEntity::getWorkspaceId, SysSettingEntity.WORKSPACE_GLOBAL)
                        .eq(SysSettingEntity::getSettingKey, KEY_EVAL_LLM)
                        .last("LIMIT 1"));
        if (row == null) {
            row = new SysSettingEntity();
            row.setWorkspaceId(SysSettingEntity.WORKSPACE_GLOBAL);
            row.setSettingKey(KEY_EVAL_LLM);
            row.setSettingValue(json);
            row.setUpdatedAt(java.time.LocalDateTime.now());
            sysSettingMapper.insert(row);
        } else {
            row.setSettingValue(json);
            row.setUpdatedAt(java.time.LocalDateTime.now());
            sysSettingMapper.updateById(row);
        }
        cachedEvalLlm = settings;
        cacheAt = System.currentTimeMillis();
    }

    /** 自动评估采样配置；未配置时返回 null（调用方回退 yml）。负结果同样进 10s 缓存。 */
    public EvalSampling getEvalSampling() {
        long now = System.currentTimeMillis();
        if (samplingCacheAt > 0 && now - samplingCacheAt < 10_000) {
            return samplingCache;
        }
        SysSettingEntity row = sysSettingMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysSettingEntity>lambdaQuery()
                        .eq(SysSettingEntity::getWorkspaceId, SysSettingEntity.WORKSPACE_GLOBAL)
                        .eq(SysSettingEntity::getSettingKey, KEY_EVAL_SAMPLING)
                        .last("LIMIT 1"));
        EvalSampling parsed = null;
        if (row != null && row.getSettingValue() != null && !row.getSettingValue().isEmpty()) {
            try {
                parsed = objectMapper.readValue(row.getSettingValue(), EvalSampling.class);
            } catch (Exception e) {
                log.warn("sys setting {} parse failed, fallback to yml", KEY_EVAL_SAMPLING);
            }
        }
        samplingCache = parsed;
        samplingCacheAt = now;
        return parsed;
    }

    public synchronized void saveEvalSampling(EvalSampling settings) {
        String json;
        try {
            json = objectMapper.writeValueAsString(settings);
        } catch (Exception e) {
            throw new EmonLensException(io.emonlens.common.ErrorCode.BAD_REQUEST, "设置序列化失败");
        }
        SysSettingEntity row = sysSettingMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysSettingEntity>lambdaQuery()
                        .eq(SysSettingEntity::getWorkspaceId, SysSettingEntity.WORKSPACE_GLOBAL)
                        .eq(SysSettingEntity::getSettingKey, KEY_EVAL_SAMPLING)
                        .last("LIMIT 1"));
        if (row == null) {
            row = new SysSettingEntity();
            row.setWorkspaceId(SysSettingEntity.WORKSPACE_GLOBAL);
            row.setSettingKey(KEY_EVAL_SAMPLING);
            row.setSettingValue(json);
            row.setUpdatedAt(java.time.LocalDateTime.now());
            sysSettingMapper.insert(row);
        } else {
            row.setSettingValue(json);
            row.setUpdatedAt(java.time.LocalDateTime.now());
            sysSettingMapper.updateById(row);
        }
        samplingCache = settings;
        samplingCacheAt = System.currentTimeMillis();
    }

    /** 数据保留策略运行时配置；未配置时返回 null（调用方回退 yml）。负结果同样进 10s 缓存。 */
    public Retention getRetention() {
        long now = System.currentTimeMillis();
        if (retentionCacheAt > 0 && now - retentionCacheAt < 10_000) {
            return retentionCache;
        }
        SysSettingEntity row = sysSettingMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysSettingEntity>lambdaQuery()
                        .eq(SysSettingEntity::getWorkspaceId, SysSettingEntity.WORKSPACE_GLOBAL)
                        .eq(SysSettingEntity::getSettingKey, KEY_RETENTION)
                        .last("LIMIT 1"));
        Retention parsed = null;
        if (row != null && row.getSettingValue() != null && !row.getSettingValue().isEmpty()) {
            try {
                parsed = objectMapper.readValue(row.getSettingValue(), Retention.class);
            } catch (Exception e) {
                log.warn("sys setting {} parse failed, fallback to yml", KEY_RETENTION);
            }
        }
        retentionCache = parsed;
        retentionCacheAt = now;
        return parsed;
    }

    public synchronized void saveRetention(Retention settings) {
        String json;
        try {
            json = objectMapper.writeValueAsString(settings);
        } catch (Exception e) {
            throw new EmonLensException(io.emonlens.common.ErrorCode.BAD_REQUEST, "设置序列化失败");
        }
        SysSettingEntity row = sysSettingMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysSettingEntity>lambdaQuery()
                        .eq(SysSettingEntity::getWorkspaceId, SysSettingEntity.WORKSPACE_GLOBAL)
                        .eq(SysSettingEntity::getSettingKey, KEY_RETENTION)
                        .last("LIMIT 1"));
        if (row == null) {
            row = new SysSettingEntity();
            row.setWorkspaceId(SysSettingEntity.WORKSPACE_GLOBAL);
            row.setSettingKey(KEY_RETENTION);
            row.setSettingValue(json);
            row.setUpdatedAt(java.time.LocalDateTime.now());
            sysSettingMapper.insert(row);
        } else {
            row.setSettingValue(json);
            row.setUpdatedAt(java.time.LocalDateTime.now());
            sysSettingMapper.updateById(row);
        }
        retentionCache = settings;
        retentionCacheAt = System.currentTimeMillis();
    }

    /** 数据保留策略（天数；null/0 = 不清理该类数据） */
    @lombok.Data
    public static class Retention {
        private Integer spanDays;
        private Integer contentDays;
        private Integer eventDays;
        private Integer scoreDays;
        private Integer notifyRecordDays;
        private Integer healthSnapshotDays;
    }

    /** 自动评估采样参数（LangSmith 式 sampling） */
    @lombok.Data
    public static class EvalSampling {
        /** 0-1；0=关闭自动评估（错误 trace 仍评估） */
        private Double defaultRate;
        private Integer maxPerHour;
        private Boolean autoRunEnabled;
    }

    private EvalLlm parse(SysSettingEntity row) {
        if (row == null || row.getSettingValue() == null || row.getSettingValue().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(row.getSettingValue(), EvalLlm.class);
        } catch (Exception e) {
            log.warn("sys setting {} parse failed, fallback to yml", KEY_EVAL_LLM);
            return null;
        }
    }

    /**
     * LLM 端点配置。protocol 决定 wire protocol（{@code openai} = /chat/completions，
     * {@code anthropic} = /messages）；apiKey 为空表示未设置；mask 用于回显。
     */
    @lombok.Data
    public static class EvalLlm {
        /** openai | anthropic；空 = openai（兼容旧配置）。 */
        private String protocol;
        private String baseUrl;
        private String apiKey;
        private String model;
        private Long timeoutMs;

        public boolean configured() {
            return baseUrl != null && !baseUrl.isEmpty();
        }
    }
}
