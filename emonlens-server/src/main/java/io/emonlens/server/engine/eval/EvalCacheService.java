package io.emonlens.server.engine.eval;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.emonlens.common.Sha256Utils;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.config.SysSettingService;
import io.emonlens.server.storage.entity.EvalCacheEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.mapper.EvalCacheMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM Judge 结果缓存。
 *
 * <p>key 绑定：样本内容（caseHash + candidate + traceDigest + retrieval/tools/turns/expectations）
 * + evaluator spec + 模板 hash + judge model + temperature + judgeMode。
 * 任一语义字段变化都会得到新 key；因此缓存是“可审计的确定性复用”，不是静默复用。</p>
 *
 * <p>默认关闭。temperature>0 的 Judge 默认不缓存；缓存命中不产生成本，也不计入 Judge 调用数。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalCacheService {

    public static final String PROTOCOL = "judge-cache/v1";

    private final EvalCacheMapper evalCacheMapper;
    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;
    private final SysSettingService sysSettingService;
    private final JudgeTemplateService judgeTemplateService;

    @Data
    public static class CacheKey {
        private String key;
        private String sampleHash;
        private String evaluatorFingerprint;
        private String judgeModel;
        private String templateHash;
    }

    /** 返回 null 表示该次调用不参与缓存（关闭/非 Judge/随机温度/模板不可解析）。 */
    public CacheKey key(String workspaceId, EvaluatorEntity evaluator, EvaluationSample sample) {
        if (!properties.getEval().isCacheEnabled() || evaluator == null || sample == null
                || !EvaluatorEntity.TYPE_LLM_JUDGE.equals(evaluator.getType())) {
            return null;
        }
        try {
            EvaluatorSpec spec = objectMapper.readValue(evaluator.getSpecJson(), EvaluatorSpec.class);
            double temperature = spec.getTemperature() == null ? 0.0 : spec.getTemperature();
            if (temperature > 0 && !properties.getEval().isCacheAllowStochastic()) {
                return null;
            }
            JudgeTemplate template = judgeTemplateService.resolve(workspaceId,
                    spec.getJudgeTemplateKey(), spec.getJudgeTemplateVersion());
            CacheKey result = new CacheKey();
            result.sampleHash = sampleHash(sample);
            result.templateHash = template.getHash();
            result.evaluatorFingerprint = Sha256Utils.hash(
                    canonical(spec) + "|" + template.getHash());
            result.judgeModel = spec.getJudgeModel() == null || spec.getJudgeModel().isBlank()
                    ? properties.getEval().getLlm().getModel() : spec.getJudgeModel();
            String mode = spec.getJudgeMode() == null || spec.getJudgeMode().isBlank()
                    ? "HOLISTIC" : spec.getJudgeMode().toUpperCase();
            result.key = Sha256Utils.hash(PROTOCOL + "|" + result.sampleHash + "|"
                    + result.evaluatorFingerprint + "|" + result.judgeModel + "|"
                    + temperature + "|" + mode + "|" + providerFingerprint());
            return result;
        } catch (Exception e) {
            log.debug("eval cache key skipped: {}", e.getMessage());
            return null;
        }
    }

    public EvalResultEntity get(String workspaceId, CacheKey key) {
        if (key == null || key.getKey() == null) {
            return null;
        }
        EvalCacheEntity entity = evalCacheMapper.selectOne(Wrappers.<EvalCacheEntity>lambdaQuery()
                .eq(EvalCacheEntity::getWorkspaceId, workspaceId)
                .eq(EvalCacheEntity::getCacheKey, key.getKey())
                .last("LIMIT 1"));
        if (entity == null) {
            return null;
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        EvalResultEntity result = parse(entity.getResultJson());
        if (result == null) {
            result = new EvalResultEntity();
            result.setReason("缓存命中（原始结果不可解析）");
        }
        result.setLabel(entity.getLabel());
        result.setScore(entity.getScore());
        result.setReason(entity.getReason());
        result.setPromptTokens(0);
        result.setCompletionTokens(0);
        result.setJudgeCalled(false);
        result.setCacheHit(true);
        result.setCacheKey(key.getKey());
        result.setJudgeTemplateKey(result.getJudgeTemplateKey());
        result.setJudgeTemplateHash(key.getTemplateHash());
        return result;
    }

    public void put(String workspaceId, CacheKey key, EvalResultEntity result) {
        if (key == null || key.getKey() == null || result == null
                || EvaluatorEntity.LABEL_ERROR.equals(result.getLabel())
                || EvaluatorEntity.LABEL_EVAL_UNAVAILABLE.equals(result.getLabel())
                || EvaluatorEntity.LABEL_NOT_APPLICABLE.equals(result.getLabel())) {
            return;
        }
        // 瞬态不可用/非法输出不是可复现结论，缓存会毒化后续运行
        if (EvaluationRunService.RC_JUDGE_UNAVAILABLE.equals(result.getReasonCode())
                || EvaluationRunService.RC_INVALID_JUDGE_OUTPUT.equals(result.getReasonCode())) {
            return;
        }
        try {
            result.setCacheKey(key.getKey());
            EvalCacheEntity entity = new EvalCacheEntity();
            entity.setWorkspaceId(workspaceId);
            entity.setCacheKey(key.getKey());
            entity.setSampleHash(key.getSampleHash());
            entity.setEvaluatorFingerprint(key.getEvaluatorFingerprint());
            entity.setJudgeModel(key.getJudgeModel());
            entity.setTemplateHash(key.getTemplateHash());
            entity.setProtocolVersion(PROTOCOL);
            entity.setLabel(result.getLabel() == null ? "UNKNOWN" : result.getLabel());
            entity.setScore(result.getScore());
            entity.setReason(clip(result.getReason(), 1000));
            entity.setResultJson(objectMapper.writeValueAsString(result));
            entity.setPromptTokens(result.getPromptTokens());
            entity.setCompletionTokens(result.getCompletionTokens());
            entity.setCreatedAt(LocalDateTime.now());
            int ttl = Math.max(0, properties.getEval().getCacheTtlHours());
            entity.setExpiresAt(LocalDateTime.now().plusHours(ttl));
            evalCacheMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 并发 worker 同时写同一 key：保留先写入的结果
        } catch (Exception e) {
            log.warn("eval cache write failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${emonlens.eval.cache-cleanup-cron:0 10 3 * * *}")
    public void cleanup() {
        int deleted = evalCacheMapper.delete(Wrappers.<EvalCacheEntity>lambdaQuery()
                .isNotNull(EvalCacheEntity::getExpiresAt)
                .lt(EvalCacheEntity::getExpiresAt, LocalDateTime.now()));
        if (deleted > 0) {
            log.info("eval cache cleanup removed {} expired rows", deleted);
        }
    }

    private EvalResultEntity parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, EvalResultEntity.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 同一模型名在不同 gateway/协议下语义可能不同，纳入 provider 指纹避免跨端点误命中。 */
    private String providerFingerprint() {
        EmonLensProperties.Eval.Llm yml = properties.getEval().getLlm();
        SysSettingService.EvalLlm db = sysSettingService.getEvalLlm();
        String protocol = db != null && db.getProtocol() != null && !db.getProtocol().isBlank()
                ? db.getProtocol() : yml.getProtocol();
        String baseUrl = db != null && db.getBaseUrl() != null && !db.getBaseUrl().isBlank()
                ? db.getBaseUrl() : yml.getBaseUrl();
        return Sha256Utils.hash((protocol == null ? "" : protocol) + "|"
                + (baseUrl == null ? "" : baseUrl));
    }

    private String sampleHash(EvaluationSample sample) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", sample.getSchemaVersion());
        value.put("caseHash", sample.getCaseHash());
        value.put("input", sample.getInput());
        value.put("candidateOutput", sample.getCandidateOutput());
        value.put("expectedOutput", sample.getExpectedOutput());
        value.put("traceDigest", sample.getTraceDigest());
        value.put("retrievalContext", sample.getRetrievalContext());
        value.put("expectedTools", sample.getExpectedTools());
        value.put("toolsCalled", sample.getToolsCalled());
        value.put("turns", sample.getTurns());
        value.put("expectations", sample.getExpectations());
        return Sha256Utils.hash(canonical(value));
    }

    private String canonical(Object value) {
        try {
            ObjectMapper canonical = objectMapper.copy();
            canonical.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return canonical.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("canonical json failed", e);
        }
    }

    private String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
