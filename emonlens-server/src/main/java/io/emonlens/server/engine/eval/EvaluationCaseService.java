package io.emonlens.server.engine.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.storage.entity.DatasetItemEntity;

import lombok.RequiredArgsConstructor;

/**
 * 评测样本契约 v2：校验、解析、canonical hash。
 *
 * <p>所有新字段在数据库里都是 JSON 字符串；本类负责把“写入口”的校验和
 * “读路径/快照”的解析集中在一处，避免 controller、revision、snapshot 各写一套。</p>
 *
 * <p>旧样本（schemaVersion=1、新字段全空）不经过任何新逻辑；解析失败也只影响
 * 本次写入，不会回写或拒绝历史数据。</p>
 */
@Service
@RequiredArgsConstructor
public class EvaluationCaseService {

    public static final int SCHEMA_V1 = 1;
    public static final int SCHEMA_V2 = 2;

    private static final int MAX_RETRIEVAL_CONTEXTS = 20;
    private static final int MAX_TOOLS = 50;
    private static final int MAX_TURNS = 50;
    private static final int MAX_EXPECTATIONS = 20;
    private static final int MAX_TEXT_CHARS = 8000;
    private static final int MAX_SHORT_CHARS = 500;
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");

    private final ObjectMapper objectMapper;

    public int schemaVersion(Integer value) {
        return value == null ? SCHEMA_V1 : value;
    }

    /** 有 v2 字段但未显式声明版本时，自动升级为 2；显式写 1 则拒绝。 */
    public int effectiveSchemaVersion(Integer requested, boolean hasV2Fields) {
        if (requested == null) {
            return hasV2Fields ? SCHEMA_V2 : SCHEMA_V1;
        }
        int version = requested;
        if (version != SCHEMA_V1 && version != SCHEMA_V2) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "schemaVersion must be 1 or 2");
        }
        if (version == SCHEMA_V1 && hasV2Fields) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "retrievalContext/expectedTools/turns/expectations 需要 schemaVersion=2");
        }
        return version;
    }

    public static boolean hasV2Fields(String retrievalContextJson, String expectedToolsJson,
                                      String turnsJson, String expectationsJson) {
        return notBlank(retrievalContextJson) || notBlank(expectedToolsJson)
                || notBlank(turnsJson) || notBlank(expectationsJson);
    }

    /**
     * 校验写入口。maxChars 是单样本快照预算；新字段总和超过它时直接拒绝，
     * 避免“创建成功、运行快照被裁剪成 UNKNOWN”的隐性失败。
     */
    public void validate(int schemaVersion, String input, String expectedOutput, String metadataJson,
                         String retrievalContextJson, String expectedToolsJson,
                         String turnsJson, String expectationsJson, int maxChars) {
        if (schemaVersion == SCHEMA_V1) {
            if (hasV2Fields(retrievalContextJson, expectedToolsJson, turnsJson, expectationsJson)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "schemaVersion=1 不允许填写 v2 字段；请使用 schemaVersion=2");
            }
            return;
        }
        if (notBlank(metadataJson) && !isJsonObject(metadataJson)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "metadataJson 必须是 JSON 对象");
        }
        validateRetrievalContext(retrievalContextJson);
        validateExpectedTools(expectedToolsJson);
        validateTurns(turnsJson);
        validateExpectations(expectationsJson);

        int total = length(input) + length(expectedOutput) + length(metadataJson)
                + length(retrievalContextJson) + length(expectedToolsJson)
                + length(turnsJson) + length(expectationsJson);
        if (total > maxChars) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "样本契约字段总长度 " + total + " 超过快照上限 " + maxChars
                            + " 字符，请缩短或拆分样本");
        }
    }

    public String hash(DatasetItemEntity item) {
        if (item == null) {
            return null;
        }
        int version = schemaVersion(item.getSchemaVersion());
        return hash(version, item.getTraceId(), item.getInput(), item.getExpectedOutput(),
                item.getMetadataJson(), item.getPurposeTags(), item.getRiskLevel(),
                item.getGroupKey(), item.getSplit(), item.getFailureMode(),
                item.getRetrievalContextJson(), item.getExpectedToolsJson(),
                item.getTurnsJson(), item.getExpectationsJson());
    }

    public String hash(int schemaVersion, String traceId, String input, String expectedOutput,
                       String metadataJson, String purposeTags, String riskLevel,
                       String groupKey, String split, String failureMode,
                       String retrievalContextJson, String expectedToolsJson,
                       String turnsJson, String expectationsJson) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", schemaVersion);
        value.put("traceId", traceId);
        value.put("input", input);
        value.put("expectedOutput", expectedOutput);
        value.put("metadata", parseForHash(metadataJson));
        value.put("purposeTags", purposeTags);
        value.put("riskLevel", riskLevel);
        value.put("groupKey", groupKey);
        value.put("split", split);
        value.put("failureMode", failureMode);
        value.put("retrievalContext", parseForHash(retrievalContextJson));
        value.put("expectedTools", parseForHash(expectedToolsJson));
        value.put("turns", parseForHash(turnsJson));
        value.put("expectations", parseForHash(expectationsJson));
        return Sha256Utils.hash(canonicalJson(value));
    }

    public List<Map<String, Object>> list(String json, String field) {
        if (!notBlank(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 必须是 JSON 数组");
        }
    }

    public Map<String, Object> object(String json, String field) {
        if (!notBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 必须是 JSON 对象");
        }
    }

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            String text = (String) value;
            return text.isBlank() ? null : text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "字段不是合法 JSON");
        }
    }

    /** 供 API 返回：把 JSON 字符串解析为对象；历史脏数据原样返回，不让列表接口整体失败。 */
    public Object readForResponse(String json) {
        if (!notBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    public String canonicalJson(Object value) {
        try {
            ObjectMapper canonical = objectMapper.copy();
            canonical.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return canonical.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "canonical json failed");
        }
    }

    // ==================== 字段校验 ====================

    private void validateRetrievalContext(String json) {
        if (!notBlank(json)) {
            return;
        }
        JsonNode root = parseJson(json, "retrievalContext");
        if (!root.isArray()) {
            throw bad("retrievalContext 必须是数组");
        }
        if (root.size() > MAX_RETRIEVAL_CONTEXTS) {
            throw bad("retrievalContext 最多 " + MAX_RETRIEVAL_CONTEXTS + " 条");
        }
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            if (node == null || !node.isObject()) {
                throw bad("retrievalContext[" + i + "] 必须是对象");
            }
            String content = text(node, "content");
            if (!notBlank(content)) {
                throw bad("retrievalContext[" + i + "].content 必填");
            }
            if (content.length() > MAX_TEXT_CHARS) {
                throw bad("retrievalContext[" + i + "].content 超过 " + MAX_TEXT_CHARS + " 字符");
            }
            checkShort(node, "id", i, "retrievalContext");
            checkShort(node, "source", i, "retrievalContext");
            JsonNode score = node.get("score");
            if (score != null && !score.isNull()) {
                if (!score.isNumber() || score.asDouble() < 0 || score.asDouble() > 1) {
                    throw bad("retrievalContext[" + i + "].score 必须在 0-1 之间");
                }
            }
        }
    }

    private void validateExpectedTools(String json) {
        if (!notBlank(json)) {
            return;
        }
        JsonNode root = parseJson(json, "expectedTools");
        if (!root.isArray()) {
            throw bad("expectedTools 必须是数组");
        }
        if (root.size() > MAX_TOOLS) {
            throw bad("expectedTools 最多 " + MAX_TOOLS + " 条");
        }
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            if (node == null || !node.isObject()) {
                throw bad("expectedTools[" + i + "] 必须是对象");
            }
            String name = text(node, "name");
            if (!notBlank(name) || !TOOL_NAME.matcher(name).matches()) {
                throw bad("expectedTools[" + i + "].name 非法");
            }
            JsonNode type = node.get("type");
            if (type != null && !type.isNull()) {
                String value = type.asText();
                if (!"FUNCTION".equals(value) && !"MCP".equals(value)) {
                    throw bad("expectedTools[" + i + "].type 只能是 FUNCTION/MCP");
                }
            }
            JsonNode required = node.get("required");
            if (required != null && !required.isNull() && !required.isBoolean()) {
                throw bad("expectedTools[" + i + "].required 必须是布尔值");
            }
            JsonNode params = node.get("inputParameters");
            if (params != null && !params.isNull() && !params.isObject()) {
                throw bad("expectedTools[" + i + "].inputParameters 必须是 JSON 对象");
            }
            if (params != null && !params.isNull() && params.toString().length() > 4000) {
                throw bad("expectedTools[" + i + "].inputParameters 超过 4000 字符");
            }
        }
    }

    private void validateTurns(String json) {
        if (!notBlank(json)) {
            return;
        }
        JsonNode root = parseJson(json, "turns");
        if (!root.isArray()) {
            throw bad("turns 必须是数组");
        }
        if (root.size() > MAX_TURNS) {
            throw bad("turns 最多 " + MAX_TURNS + " 轮");
        }
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            if (node == null || !node.isObject()) {
                throw bad("turns[" + i + "] 必须是对象");
            }
            String role = text(node, "role");
            if (!"user".equals(role) && !"assistant".equals(role)
                    && !"tool".equals(role) && !"system".equals(role)) {
                throw bad("turns[" + i + "].role 只能是 user/assistant/tool/system");
            }
            String content = text(node, "content");
            if (content == null) {
                throw bad("turns[" + i + "].content 必填");
            }
            if (content.length() > MAX_TEXT_CHARS) {
                throw bad("turns[" + i + "].content 超过 " + MAX_TEXT_CHARS + " 字符");
            }
            JsonNode tools = node.get("toolsCalled");
            if (tools != null && !tools.isNull() && !tools.isArray()) {
                throw bad("turns[" + i + "].toolsCalled 必须是数组");
            }
        }
    }

    private void validateExpectations(String json) {
        if (!notBlank(json)) {
            return;
        }
        JsonNode root = parseJson(json, "expectations");
        if (!root.isObject()) {
            throw bad("expectations 必须是对象");
        }
        checkConditions(root, "must");
        checkConditions(root, "mustNot");
        JsonNode policy = root.get("unknownPolicy");
        if (policy != null && !policy.isNull()) {
            String value = policy.asText();
            if (!"PASS".equals(value) && !"FAIL".equals(value) && !"BLOCK".equals(value)) {
                throw bad("expectations.unknownPolicy 只能是 PASS/FAIL/BLOCK");
            }
        }
    }

    private void checkConditions(JsonNode root, String field) {
        JsonNode conditions = root.get(field);
        if (conditions == null || conditions.isNull()) {
            return;
        }
        if (!conditions.isArray()) {
            throw bad("expectations." + field + " 必须是数组");
        }
        if (conditions.size() > MAX_EXPECTATIONS) {
            throw bad("expectations." + field + " 最多 " + MAX_EXPECTATIONS + " 条");
        }
        for (int i = 0; i < conditions.size(); i++) {
            JsonNode condition = conditions.get(i);
            if (condition == null || !condition.isTextual() || condition.asText().isBlank()) {
                throw bad("expectations." + field + "[" + i + "] 必须是非空字符串");
            }
            if (condition.asText().length() > MAX_SHORT_CHARS) {
                throw bad("expectations." + field + "[" + i + "] 超过 " + MAX_SHORT_CHARS + " 字符");
            }
        }
    }

    private void checkShort(JsonNode node, String field, int index, String parent) {
        String value = text(node, field);
        if (value != null && value.length() > 255) {
            throw bad(parent + "[" + index + "]." + field + " 超过 255 字符");
        }
    }

    private JsonNode parseJson(String json, String field) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw bad(field + " 不是合法 JSON");
        }
    }

    private boolean isJsonObject(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject();
        } catch (Exception e) {
            return false;
        }
    }

    private Object parseForHash(String json) {
        if (!notBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isTextual() ? value.asText() : value.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    private static EmonLensException bad(String message) {
        return new EmonLensException(ErrorCode.BAD_REQUEST, message);
    }
}
