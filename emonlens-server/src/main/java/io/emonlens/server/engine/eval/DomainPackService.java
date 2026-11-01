package io.emonlens.server.engine.eval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.api.DomainPackController.CreateRequest;
import io.emonlens.server.storage.entity.DomainPackEntity;
import io.emonlens.server.storage.mapper.DomainPackMapper;

import lombok.RequiredArgsConstructor;

/**
 * 领域包注册表。核心只认 qualityModel/riskTaxonomy/datasetBlueprint/gateTemplates
 * 这些声明；行业差异不写进核心 if/else。版本不可变，deprecate 只改状态。
 */
@Service
@RequiredArgsConstructor
public class DomainPackService {

    public static final String SCHEMA_VERSION = "domain-pack/v1";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DEPRECATED = "DEPRECATED";

    private static final Pattern PACK_KEY = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Set<String> MANIFEST_KEYS = Set.of(
            "schemaVersion", "qualityModel", "riskTaxonomy", "datasetBlueprint", "gateTemplates");
    private static final Set<String> DIMENSION_KEYS = Set.of("key", "label", "weight", "dependsOn", "description");

    private final DomainPackMapper domainPackMapper;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> create(String workspaceId, CreateRequest request) {
        if (request == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "request is required");
        }
        String packKey = normalizePackKey(request.getPackKey());
        String version = normalizeVersion(request.getVersion());
        String name = required(request.getName(), "name", 200);
        String description = trimToNull(request.getDescription());
        if (description != null && description.length() > 1000) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "description 超过最大长度 1000");
        }
        if (request.getManifest() == null || request.getManifest().isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "manifest is required");
        }
        Map<String, Object> manifest = normalizeManifest(request.getManifest());
        String schemaVersion = asString(manifest.get("schemaVersion"));
        String digestValue = digest(toJson(canonicalize(
                envelope(packKey, version, name, description, manifest))));

        DomainPackEntity existing = domainPackMapper.selectOne(Wrappers.<DomainPackEntity>lambdaQuery()
                .eq(DomainPackEntity::getWorkspaceId, workspaceId)
                .eq(DomainPackEntity::getPackKey, packKey)
                .eq(DomainPackEntity::getVersion, version));
        if (existing != null) {
            if (Objects.equals(existing.getDigest(), digestValue)) {
                return detail(workspaceId, existing.getId());
            }
            throw new EmonLensException(ErrorCode.CONFLICT,
                    "领域包版本已存在且内容不同: " + packKey + "@" + version);
        }

        DomainPackEntity entity = new DomainPackEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setPackKey(packKey);
        entity.setVersion(version);
        entity.setSchemaVersion(schemaVersion);
        entity.setName(name);
        entity.setDescription(description);
        entity.setManifestJson(toJson(manifest));
        entity.setDigest(digestValue);
        entity.setStatus(STATUS_PUBLISHED);
        entity.setCompatibility(trimToNull(request.getCompatibility()));
        entity.setCreatedBy(request.getCreatedBy());
        entity.setCreatedAt(LocalDateTime.now());
        try {
            domainPackMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new EmonLensException(ErrorCode.CONFLICT,
                    "领域包版本已存在: " + packKey + "@" + version);
        }
        return detail(workspaceId, entity.getId());
    }

    public List<Map<String, Object>> list(String workspaceId, String packKey) {
        String normalized = trimToNull(packKey);
        if (normalized != null) {
            normalized = normalizePackKey(normalized);
        }
        List<DomainPackEntity> rows = domainPackMapper.selectList(Wrappers.<DomainPackEntity>lambdaQuery()
                .eq(DomainPackEntity::getWorkspaceId, workspaceId)
                .eq(normalized != null, DomainPackEntity::getPackKey, normalized)
                .orderByAsc(DomainPackEntity::getPackKey)
                .orderByDesc(DomainPackEntity::getCreatedAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (DomainPackEntity row : rows) {
            result.add(metadata(row));
        }
        return result;
    }

    public List<Map<String, Object>> versions(String workspaceId, String packKey) {
        String normalized = normalizePackKey(packKey);
        List<DomainPackEntity> rows = domainPackMapper.selectList(Wrappers.<DomainPackEntity>lambdaQuery()
                .eq(DomainPackEntity::getWorkspaceId, workspaceId)
                .eq(DomainPackEntity::getPackKey, normalized)
                .orderByDesc(DomainPackEntity::getCreatedAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (DomainPackEntity row : rows) {
            result.add(metadata(row));
        }
        return result;
    }

    public Map<String, Object> detail(String workspaceId, String id) {
        DomainPackEntity entity = require(workspaceId, id);
        Map<String, Object> map = metadata(entity);
        map.put("manifest", parseMap(entity.getManifestJson()));
        return map;
    }

    @Transactional
    public Map<String, Object> deprecate(String workspaceId, String id) {
        DomainPackEntity entity = require(workspaceId, id);
        if (STATUS_DEPRECATED.equals(entity.getStatus())) {
            return detail(workspaceId, id);
        }
        entity.setStatus(STATUS_DEPRECATED);
        entity.setDeprecatedAt(LocalDateTime.now());
        domainPackMapper.updateById(entity);
        return detail(workspaceId, id);
    }

    private DomainPackEntity require(String workspaceId, String id) {
        DomainPackEntity entity = domainPackMapper.selectById(id);
        if (entity == null || !Objects.equals(entity.getWorkspaceId(), workspaceId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "domain pack not found");
        }
        return entity;
    }

    private Map<String, Object> metadata(DomainPackEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("packKey", e.getPackKey());
        map.put("version", e.getVersion());
        map.put("schemaVersion", e.getSchemaVersion());
        map.put("name", e.getName());
        map.put("description", e.getDescription());
        map.put("digest", e.getDigest());
        map.put("status", e.getStatus());
        map.put("compatibility", e.getCompatibility());
        map.put("createdBy", e.getCreatedBy());
        map.put("createdAt", e.getCreatedAt());
        map.put("deprecatedAt", e.getDeprecatedAt());
        return map;
    }

    private Map<String, Object> envelope(String packKey, String version, String name,
                                         String description, Map<String, Object> manifest) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("packKey", packKey);
        envelope.put("version", version);
        envelope.put("schemaVersion", manifest.get("schemaVersion"));
        envelope.put("name", name);
        envelope.put("description", description);
        envelope.put("manifest", manifest);
        return envelope;
    }

    private Map<String, Object> normalizeManifest(Map<String, Object> raw) {
        for (String key : raw.keySet()) {
            if (!MANIFEST_KEYS.contains(key)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "未知 manifest 字段: " + key);
            }
        }
        String schema = trimToNull(asString(raw.get("schemaVersion")));
        if (!SCHEMA_VERSION.equals(schema)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "schemaVersion 必须为 " + SCHEMA_VERSION);
        }

        Map<String, Object> qualityModel = mapValue(raw.get("qualityModel"), "qualityModel");
        List<Map<String, Object>> dimensions = listValue(qualityModel.get("dimensions"), "qualityModel.dimensions");
        if (dimensions.isEmpty() || dimensions.size() > 50) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "qualityModel.dimensions 数量必须在 1..50");
        }
        Set<String> dimensionKeys = new LinkedHashSet<>();
        Map<String, List<String>> dependencies = new HashMap<>();
        for (Map<String, Object> dimension : dimensions) {
            for (String key : dimension.keySet()) {
                if (!DIMENSION_KEYS.contains(key)) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST, "未知 dimension 字段: " + key);
                }
            }
            String key = normalizeKey(dimension.get("key"), "dimension.key");
            if (!dimensionKeys.add(key)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "重复 dimension.key: " + key);
            }
            required(dimension.get("label"), "dimension.label", 100);
            int weight = intValue(dimension.get("weight"), 1);
            if (weight < 1 || weight > 100) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "dimension.weight 必须在 1..100");
            }
            List<String> dependsOn = stringList(dimension.get("dependsOn"), "dimension.dependsOn");
            for (String dep : dependsOn) {
                if (!PACK_KEY.matcher(dep).matches()) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST, "非法 dependsOn: " + dep);
                }
            }
            dependencies.put(key, dependsOn);
        }
        for (Map.Entry<String, List<String>> e : dependencies.entrySet()) {
            for (String dep : e.getValue()) {
                if (dep.equals(e.getKey())) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST,
                            "dimension 不能依赖自己: " + dep);
                }
                if (!dimensionKeys.contains(dep)) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST,
                            "dependsOn 指向不存在的维度: " + dep);
                }
            }
        }
        detectCycle(dependencies);

        List<Map<String, Object>> riskTaxonomy = normalizeRiskTaxonomy(raw.get("riskTaxonomy"));
        Map<String, Object> blueprint = normalizeBlueprint(raw.get("datasetBlueprint"), riskTaxonomy);
        List<Map<String, Object>> gateTemplates = normalizeGateTemplates(raw.get("gateTemplates"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", schema);
        Map<String, Object> qm = new LinkedHashMap<>();
        qm.put("dimensions", dimensions);
        result.put("qualityModel", qm);
        result.put("riskTaxonomy", riskTaxonomy);
        result.put("datasetBlueprint", blueprint);
        result.put("gateTemplates", gateTemplates);
        return result;
    }

    private List<Map<String, Object>> normalizeRiskTaxonomy(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = listValue(raw, "riskTaxonomy");
        if (rows.size() > 50) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "riskTaxonomy 最多 50 项");
        }
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : rows) {
            for (String key : row.keySet()) {
                if (!Set.of("key", "label", "description").contains(key)) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST, "未知 risk 字段: " + key);
                }
            }
            String key = normalizeKey(row.get("key"), "risk.key");
            if (!keys.add(key)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "重复 risk.key: " + key);
            }
            required(row.get("label"), "risk.label", 100);
        }
        return rows;
    }

    private Map<String, Object> normalizeBlueprint(Object raw, List<Map<String, Object>> riskTaxonomy) {
        Map<String, Object> blueprint = raw == null ? Map.of() : mapValue(raw, "datasetBlueprint");
        for (String key : blueprint.keySet()) {
            if (!Set.of("minItems", "riskMin", "splitMin", "purposeMin", "maxStaleDays",
                    "maxItemAgeDays", "autoQuarantineStale").contains(key)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "未知 datasetBlueprint 字段: " + key);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("minItems", nonNegativeInt(blueprint.get("minItems"), 0, "datasetBlueprint.minItems"));
        result.put("maxStaleDays", positiveInt(blueprint.get("maxStaleDays"), 90,
                "datasetBlueprint.maxStaleDays"));
        result.put("riskMin", nonNegativeMap(blueprint.get("riskMin"), "datasetBlueprint.riskMin"));
        result.put("splitMin", nonNegativeMap(blueprint.get("splitMin"), "datasetBlueprint.splitMin"));
        result.put("purposeMin", nonNegativeMap(blueprint.get("purposeMin"), "datasetBlueprint.purposeMin"));
        result.put("maxItemAgeDays", nonNegativeInt(blueprint.get("maxItemAgeDays"), 0,
                "datasetBlueprint.maxItemAgeDays"));
        result.put("autoQuarantineStale", Boolean.TRUE.equals(blueprint.get("autoQuarantineStale")));
        if (!riskTaxonomy.isEmpty()) {
            Set<String> risks = new HashSet<>();
            for (Map<String, Object> row : riskTaxonomy) {
                risks.add(asString(row.get("key")));
            }
            for (String risk : ((Map<String, Object>) result.get("riskMin")).keySet()) {
                if (risks.stream().noneMatch(r -> r.equalsIgnoreCase(risk))) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST,
                            "datasetBlueprint.riskMin 引用未声明风险: " + risk);
                }
            }
        }
        return result;
    }

    private List<Map<String, Object>> normalizeGateTemplates(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<Map<String, Object>> rows = listValue(raw, "gateTemplates");
        if (rows.size() > 20) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "gateTemplates 最多 20 项");
        }
        Set<String> keys = new HashSet<>();
        for (Map<String, Object> row : rows) {
            for (String key : row.keySet()) {
                if (!Set.of("key", "name", "description", "policy").contains(key)) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST, "未知 gateTemplate 字段: " + key);
                }
            }
            String key = normalizeKey(row.get("key"), "gateTemplate.key");
            if (!keys.add(key)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "重复 gateTemplate.key: " + key);
            }
            required(row.get("name"), "gateTemplate.name", 100);
            if (row.containsKey("policy")) {
                normalizeGatePolicy(row.get("policy"), key);
            }
        }
        return rows;
    }

    private Map<String, Object> normalizeGatePolicy(Object raw, String templateKey) {
        Map<String, Object> policy = mapValue(raw, "gateTemplate.policy");
        for (String key : policy.keySet()) {
            if (!Set.of("minCoverage", "maxFail", "maxUnknown", "validHours").contains(key)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "未知 gateTemplate.policy 字段: " + key);
            }
        }
        int minCoverage = intValue(policy.get("minCoverage"), 90);
        int maxFail = intValue(policy.get("maxFail"), 0);
        int maxUnknown = intValue(policy.get("maxUnknown"), 0);
        int validHours = intValue(policy.get("validHours"), 24);
        if (minCoverage < 90 || minCoverage > 100 || maxFail != 0 || maxUnknown != 0
                || validHours < 1) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "gateTemplate.policy 不能弱于 minCoverage=90/maxFail=0/maxUnknown=0: " + templateKey);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("minCoverage", minCoverage);
        normalized.put("maxFail", maxFail);
        normalized.put("maxUnknown", maxUnknown);
        normalized.put("validHours", validHours);
        return normalized;
    }

    private void detectCycle(Map<String, List<String>> dependencies) {
        Map<String, Integer> state = new HashMap<>();
        for (String key : dependencies.keySet()) {
            visit(key, dependencies, state);
        }
    }

    private void visit(String key, Map<String, List<String>> dependencies, Map<String, Integer> state) {
        Integer s = state.get(key);
        if (s != null && s == 1) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "qualityModel dimensions 存在循环依赖: " + key);
        }
        if (s != null && s == 2) {
            return;
        }
        state.put(key, 1);
        for (String dep : dependencies.getOrDefault(key, List.of())) {
            visit(dep, dependencies, state);
        }
        state.put(key, 2);
    }

    private Map<String, Object> nonNegativeMap(Object raw, String field) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, Object> map = mapValue(raw, field);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = normalizeKey(e.getKey(), field + ".key");
            result.put(key, nonNegativeInt(e.getValue(), 0, field + "." + key));
        }
        return result;
    }

    private String normalizePackKey(String value) {
        String key = trimToNull(value);
        if (key == null || !PACK_KEY.matcher(key).matches()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "packKey 必须匹配 [a-z][a-z0-9-]{1,63}");
        }
        return key;
    }

    private String normalizeVersion(String value) {
        String version = trimToNull(value);
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "version 必须为 x.y.z");
        }
        return version;
    }

    private String normalizeKey(Object value, String field) {
        String key = trimToNull(asString(value));
        if (key == null || !PACK_KEY.matcher(key).matches()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    field + " 必须匹配 [a-z][a-z0-9-]{1,63}");
        }
        return key;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object raw, String field) {
        if (!(raw instanceof Map)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 必须是对象");
        }
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listValue(Object raw, String field) {
        if (!(raw instanceof List)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 必须是数组");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : (List<Object>) raw) {
            if (!(row instanceof Map)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 的每项必须是对象");
            }
            rows.add((Map<String, Object>) row);
        }
        return rows;
    }

    private List<String> stringList(Object raw, String field) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 必须是数组");
        }
        List<String> result = new ArrayList<>();
        for (Object value : (List<?>) raw) {
            String text = trimToNull(asString(value));
            if (text == null) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 不能包含空值");
            }
            result.add(text);
        }
        return result;
    }

    private String digest(String json) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object canonicalize(Object value) {
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                sorted.put(String.valueOf(e.getKey()), canonicalize(e.getValue()));
            }
            return sorted;
        }
        if (value instanceof List) {
            List<Object> rows = new ArrayList<>();
            for (Object row : (List<?>) value) {
                rows.add(canonicalize(row));
            }
            return rows;
        }
        return value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "manifest 无法序列化");
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String required(Object value, String field, int maxLength) {
        String text = trimToNull(asString(value));
        if (text == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " is required");
        }
        if (text.length() > maxLength) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 超过最大长度 " + maxLength);
        }
        return text;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "数值字段必须是数字");
        }
        return ((Number) value).intValue();
    }

    private int nonNegativeInt(Object value, int defaultValue, String field) {
        int v = intValue(value, defaultValue);
        if (v < 0) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 不能为负");
        }
        return v;
    }

    private int positiveInt(Object value, int defaultValue, String field) {
        int v = intValue(value, defaultValue);
        if (v < 1) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 必须大于 0");
        }
        return v;
    }
}
