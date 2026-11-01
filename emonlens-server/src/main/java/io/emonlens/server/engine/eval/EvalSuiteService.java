package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.EvalSuiteEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.mapper.EvalSuiteMapper;
import io.emonlens.server.storage.mapper.EvaluatorMapper;

import lombok.RequiredArgsConstructor;

/**
 * 不可变 Eval Suite：每次内容变化追加版本；suite 内冻结 evaluator 的 spec/version/fingerprint。
 * run 绑定 suite 时从冻结快照重建 evaluator，不读取 evaluator 当前定义。
 */
@Service
@RequiredArgsConstructor
public class EvalSuiteService {

    private final EvalSuiteMapper suiteMapper;
    private final EvaluatorMapper evaluatorMapper;
    private final EmonLensProperties properties;
    private final ObjectMapper objectMapper;

    @Transactional
    public EvalSuiteEntity create(String workspaceId, String suiteKey, String name, String description,
                                  List<String> evaluatorIds) {
        String key = suiteKey == null ? "" : suiteKey.trim();
        if (key.isEmpty() || key.length() > 100
                || !key.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "suiteKey 必须为 1..100 位字母/数字/_.-，且以字母或数字开头");
        }
        List<String> ids = normalizeEvaluatorIds(evaluatorIds);
        List<EvaluatorEntity> evaluators = loadEvaluators(workspaceId, ids);
        String suiteName = name == null || name.isBlank() ? key : name.trim();
        if (suiteName.length() > 200) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "suite name 不能超过 200 字符");
        }
        String desc = description == null ? null : description.trim();
        if (desc != null && desc.length() > 500) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "suite description 不能超过 500 字符");
        }

        List<Map<String, Object>> frozen = new ArrayList<>();
        for (EvaluatorEntity evaluator : evaluators) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", evaluator.getId());
            item.put("key", evaluator.getEvaluatorKey());
            item.put("type", evaluator.getType());
            item.put("version", evaluator.getVersion() == null ? 1 : evaluator.getVersion());
            Object spec = parseJson(evaluator.getSpecJson());
            item.put("spec", spec);
            item.put("fingerprint", Sha256Utils.hash(canonicalJson(spec)));
            frozen.add(item);
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("suiteKey", key);
        spec.put("name", suiteName);
        spec.put("description", desc);
        spec.put("evaluators", frozen);
        String specJson = toJson(spec);
        String fingerprint = Sha256Utils.hash(canonicalJson(spec));

        EvalSuiteEntity existing = suiteMapper.selectOne(Wrappers.<EvalSuiteEntity>lambdaQuery()
                .eq(EvalSuiteEntity::getWorkspaceId, workspaceId)
                .eq(EvalSuiteEntity::getSuiteKey, key)
                .eq(EvalSuiteEntity::getFingerprint, fingerprint)
                .orderByDesc(EvalSuiteEntity::getVersion)
                .last("LIMIT 1"));
        if (existing != null) {
            return enrich(existing);
        }

        EvalSuiteEntity latest = suiteMapper.selectOne(Wrappers.<EvalSuiteEntity>lambdaQuery()
                .eq(EvalSuiteEntity::getWorkspaceId, workspaceId)
                .eq(EvalSuiteEntity::getSuiteKey, key)
                .orderByDesc(EvalSuiteEntity::getVersion)
                .last("LIMIT 1"));
        int nextVersion = latest == null || latest.getVersion() == null ? 1 : latest.getVersion() + 1;

        EvalSuiteEntity suite = new EvalSuiteEntity();
        suite.setWorkspaceId(workspaceId);
        suite.setSuiteKey(key);
        suite.setName(suiteName);
        suite.setDescription(desc);
        suite.setVersion(nextVersion);
        suite.setSpecJson(specJson);
        suite.setFingerprint(fingerprint);
        suite.setCreatedAt(LocalDateTime.now());
        try {
            suiteMapper.insert(suite);
        } catch (DuplicateKeyException e) {
            // 先确认并发落库的是同一内容；不同内容绝不能把对方的冻结 spec 返回给调用方。
            EvalSuiteEntity raced = suiteMapper.selectOne(Wrappers.<EvalSuiteEntity>lambdaQuery()
                    .eq(EvalSuiteEntity::getWorkspaceId, workspaceId)
                    .eq(EvalSuiteEntity::getSuiteKey, key)
                    .eq(EvalSuiteEntity::getFingerprint, fingerprint)
                    .orderByDesc(EvalSuiteEntity::getVersion)
                    .last("LIMIT 1"));
            if (raced != null) {
                return enrich(raced);
            }
            throw new EmonLensException(ErrorCode.CONFLICT,
                    "eval suite 并发创建冲突，请重试");
        }
        return enrich(suite);
    }

    public List<EvalSuiteEntity> list(String workspaceId) {
        List<EvalSuiteEntity> rows = suiteMapper.selectList(Wrappers.<EvalSuiteEntity>lambdaQuery()
                .eq(EvalSuiteEntity::getWorkspaceId, workspaceId)
                .orderByAsc(EvalSuiteEntity::getSuiteKey)
                .orderByDesc(EvalSuiteEntity::getVersion));
        List<EvalSuiteEntity> enriched = new ArrayList<>();
        for (EvalSuiteEntity row : rows) {
            enriched.add(enrich(row));
        }
        return enriched;
    }

    public EvalSuiteEntity requireOwned(String workspaceId, String suiteId) {
        EvalSuiteEntity suite = suiteId == null ? null : suiteMapper.selectById(suiteId);
        if (suite == null || !workspaceId.equals(suite.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "eval suite not found");
        }
        return enrich(suite);
    }

    /** 从 suite 的冻结快照重建 evaluator；specJson 不再读取 evaluator 当前值。 */
    public List<EvaluatorEntity> frozenEvaluators(EvalSuiteEntity suite) {
        Map<String, Object> spec = suite.getSpec() == null ? parseMap(suite.getSpecJson()) : suite.getSpec();
        List<EvaluatorEntity> result = new ArrayList<>();
        Object raw = spec == null ? null : spec.get("evaluators");
        if (!(raw instanceof List)) {
            return result;
        }
        for (Object value : (List<?>) raw) {
            Map<String, Object> item = asMap(value);
            if (item == null) {
                continue;
            }
            EvaluatorEntity evaluator = new EvaluatorEntity();
            evaluator.setId(asString(item.get("id")));
            evaluator.setEvaluatorKey(asString(item.get("key")));
            evaluator.setType(asString(item.get("type")));
            evaluator.setVersion(asInteger(item.get("version")));
            evaluator.setWorkspaceId(suite.getWorkspaceId());
            evaluator.setEnabled(1);
            evaluator.setSpecJson(toJson(item.get("spec")));
            result.add(evaluator);
        }
        return result;
    }

    public Map<String, Object> snapshot(EvalSuiteEntity suite) {
        EvalSuiteEntity value = suite.getSpec() == null ? enrich(suite) : suite;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", value.getId());
        snapshot.put("key", value.getSuiteKey());
        snapshot.put("name", value.getName());
        snapshot.put("version", value.getVersion());
        snapshot.put("fingerprint", value.getFingerprint());
        snapshot.put("evaluatorKeys", value.getEvaluatorKeys());
        snapshot.put("evaluatorCount", value.getEvaluatorCount());
        return snapshot;
    }

    private EvalSuiteEntity enrich(EvalSuiteEntity suite) {
        Map<String, Object> spec = parseMap(suite.getSpecJson());
        suite.setSpec(spec);
        List<String> keys = new ArrayList<>();
        if (spec != null && spec.get("evaluators") instanceof List) {
            for (Object raw : (List<?>) spec.get("evaluators")) {
                Map<String, Object> item = asMap(raw);
                if (item != null && item.get("key") != null) {
                    keys.add(String.valueOf(item.get("key")));
                }
            }
        }
        suite.setEvaluatorKeys(keys);
        suite.setEvaluatorCount(keys.size());
        return suite;
    }

    private List<String> normalizeEvaluatorIds(List<String> evaluatorIds) {
        if (evaluatorIds == null || evaluatorIds.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "evaluatorIds is required");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String id : evaluatorIds) {
            if (id != null && !id.isBlank()) {
                unique.add(id.trim());
            }
        }
        if (unique.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "evaluatorIds is required");
        }
        if (unique.size() > properties.getDatasetRun().getMaxEvaluators()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "单次 suite 最多 " + properties.getDatasetRun().getMaxEvaluators() + " 个评估器");
        }
        return new ArrayList<>(unique);
    }

    private List<EvaluatorEntity> loadEvaluators(String workspaceId, List<String> ids) {
        List<EvaluatorEntity> found = evaluatorMapper.selectList(Wrappers.<EvaluatorEntity>lambdaQuery()
                .eq(EvaluatorEntity::getWorkspaceId, workspaceId)
                .in(EvaluatorEntity::getId, ids));
        Map<String, EvaluatorEntity> byId = new LinkedHashMap<>();
        for (EvaluatorEntity evaluator : found) {
            byId.put(evaluator.getId(), evaluator);
        }
        List<EvaluatorEntity> result = new ArrayList<>();
        for (String id : ids) {
            EvaluatorEntity evaluator = byId.get(id);
            if (evaluator == null) {
                throw new EmonLensException(ErrorCode.NOT_FOUND, "evaluator not found: " + id);
            }
            if (evaluator.getEnabled() == null || evaluator.getEnabled() != 1) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "evaluator is disabled: " + evaluator.getEvaluatorKey());
            }
            result.add(evaluator);
        }
        return result;
    }

    private Object parseJson(String json) {
        try {
            return json == null || json.isBlank() ? Map.of() : objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "evaluator spec is not valid json");
        }
    }

    private Map<String, Object> parseMap(String json) {
        Object value = parseJson(json);
        return value instanceof Map ? castMap(value) : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "suite serialization failed");
        }
    }

    private String canonicalJson(Object value) {
        try {
            ObjectMapper canonical = objectMapper.copy();
            canonical.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return canonical.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
