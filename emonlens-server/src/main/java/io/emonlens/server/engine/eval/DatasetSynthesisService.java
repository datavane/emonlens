package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.DatasetSynthesisCandidateEntity;
import io.emonlens.server.storage.entity.DatasetSynthesisJobEntity;
import io.emonlens.server.storage.mapper.DatasetItemMapper;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.DatasetSynthesisCandidateMapper;
import io.emonlens.server.storage.mapper.DatasetSynthesisJobMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 合成数据集 job / candidate 生命周期。
 *
 * <p>生成与审批分离：worker 只写 DRAFT/REJECTED 候选；只有人工 approve 才复制为
 * ACTIVE 样本，并记录 source_type=SYNTHETIC 与 candidate 溯源。任何候选都不会自动发布。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetSynthesisService {

    public static final String SOURCE_DATASET_ITEMS = "DATASET_ITEMS";
    public static final String SOURCE_TRACE_CLUSTER = "TRACE_CLUSTER";
    public static final String SOURCE_DOCUMENTS = "DOCUMENTS";
    public static final String SOURCE_SCRATCH = "SCRATCH";

    private final DatasetSynthesisJobMapper jobMapper;
    private final DatasetSynthesisCandidateMapper candidateMapper;
    private final DatasetItemMapper datasetItemMapper;
    private final DatasetMapper datasetMapper;
    private final EvaluationCaseService evaluationCaseService;
    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;

    @Data
    public static class CreateJobResult {
        private DatasetSynthesisJobEntity job;
        private boolean created;
    }

    @Transactional
    public CreateJobResult create(String workspaceId, String datasetId, String requestKey,
                                  String sourceType, Map<String, Object> source,
                                  Map<String, Object> config, String createdBy) {
        requireDataset(workspaceId, datasetId);
        if (!properties.getDatasetSynthesis().isEnabled()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "dataset synthesis is disabled（emonlens.dataset-synthesis.enabled=false）");
        }
        String type = sourceType == null ? "" : sourceType.trim().toUpperCase();
        if (!Set.of(SOURCE_DATASET_ITEMS, SOURCE_TRACE_CLUSTER,
                SOURCE_DOCUMENTS, SOURCE_SCRATCH).contains(type)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "sourceType must be DATASET_ITEMS/TRACE_CLUSTER/DOCUMENTS/SCRATCH");
        }
        Map<String, Object> effectiveSource = source == null ? Map.of() : source;
        validateSource(type, effectiveSource);
        Map<String, Object> effectiveConfig = effectiveConfig(config);

        String key = requestKey == null || requestKey.isBlank()
                ? "syn-" + UUID.randomUUID() : requestKey.trim();
        if (key.length() > 128) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "Idempotency-Key 最长 128 字符");
        }
        DatasetSynthesisJobEntity existing = jobMapper.selectOne(
                Wrappers.<DatasetSynthesisJobEntity>lambdaQuery()
                        .eq(DatasetSynthesisJobEntity::getWorkspaceId, workspaceId)
                        .eq(DatasetSynthesisJobEntity::getRequestKey, key)
                        .last("LIMIT 1"));
        CreateJobResult result = new CreateJobResult();
        if (existing != null) {
            // 幂等键按 workspace 唯一；复用前必须确认是同一个数据集、同一份请求，
            // 否则会把另一个数据集的任务返回给调用方，候选查询随后 404。
            if (!datasetId.equals(existing.getDatasetId())
                    || !type.equals(existing.getSourceType())
                    || !sameJson(existing.getSourceJson(), toJson(effectiveSource))
                    || !sameJson(existing.getConfigJson(), toJson(effectiveConfig))) {
                throw new EmonLensException(ErrorCode.CONFLICT,
                        "Idempotency-Key 已用于不同的合成请求: " + key);
            }
            result.setJob(existing);
            result.setCreated(false);
            return result;
        }
        DatasetSynthesisJobEntity job = new DatasetSynthesisJobEntity();
        job.setWorkspaceId(workspaceId);
        job.setDatasetId(datasetId);
        job.setRequestKey(key);
        job.setSourceType(type);
        job.setSourceJson(toJson(effectiveSource));
        job.setConfigJson(toJson(effectiveConfig));
        job.setStatus(DatasetSynthesisJobEntity.STATUS_PENDING);
        job.setTotalCandidates(0);
        job.setAcceptedCount(0);
        job.setRejectedCount(0);
        job.setModel(effectiveModel(effectiveConfig));
        job.setCreatedBy(createdBy);
        job.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(job);
        result.setJob(job);
        result.setCreated(true);
        return result;
    }

    public List<DatasetSynthesisJobEntity> listJobs(String workspaceId, String datasetId) {
        requireDataset(workspaceId, datasetId);
        return jobMapper.selectList(Wrappers.<DatasetSynthesisJobEntity>lambdaQuery()
                .eq(DatasetSynthesisJobEntity::getWorkspaceId, workspaceId)
                .eq(DatasetSynthesisJobEntity::getDatasetId, datasetId)
                .orderByDesc(DatasetSynthesisJobEntity::getCreatedAt));
    }

    public DatasetSynthesisJobEntity getJob(String workspaceId, String datasetId, String jobId) {
        DatasetSynthesisJobEntity job = jobMapper.selectById(jobId);
        if (job == null || !workspaceId.equals(job.getWorkspaceId())
                || !datasetId.equals(job.getDatasetId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "synthesis job not found");
        }
        return job;
    }

    public List<DatasetSynthesisCandidateEntity> listCandidates(String workspaceId, String datasetId,
                                                                String jobId, String status) {
        getJob(workspaceId, datasetId, jobId);
        return candidateMapper.selectList(Wrappers.<DatasetSynthesisCandidateEntity>lambdaQuery()
                .eq(DatasetSynthesisCandidateEntity::getWorkspaceId, workspaceId)
                .eq(DatasetSynthesisCandidateEntity::getJobId, jobId)
                .eq(status != null && !status.isBlank(),
                        DatasetSynthesisCandidateEntity::getStatus,
                        status == null ? null : status.trim().toUpperCase())
                .orderByAsc(DatasetSynthesisCandidateEntity::getCreatedAt));
    }

    @Transactional
    public DatasetItemEntity approve(String workspaceId, String datasetId, String jobId,
                                     String candidateId, String reviewer) {
        DatasetSynthesisJobEntity job = getJob(workspaceId, datasetId, jobId);
        DatasetSynthesisCandidateEntity candidate = requireCandidate(workspaceId, jobId, candidateId);
        if (DatasetSynthesisCandidateEntity.STATUS_APPROVED.equals(candidate.getStatus())
                && candidate.getCreatedItemId() != null) {
            DatasetItemEntity existing = datasetItemMapper.selectById(candidate.getCreatedItemId());
            if (existing != null) {
                return existing;
            }
        }
        if (!DatasetSynthesisCandidateEntity.STATUS_DRAFT.equals(candidate.getStatus())) {
            throw new EmonLensException(ErrorCode.CONFLICT,
                    "candidate is not DRAFT: " + candidate.getStatus());
        }
        Map<String, Object> payload = parseMap(candidate.getPayloadJson());
        DatasetItemEntity item = payloadToItem(datasetId, payload);
        item.setContentHash(evaluationCaseService.hash(item));
        if (item.getContentHash() != null) {
            DatasetItemEntity duplicate = datasetItemMapper.selectOne(
                    Wrappers.<DatasetItemEntity>lambdaQuery()
                            .eq(DatasetItemEntity::getDatasetId, datasetId)
                            .eq(DatasetItemEntity::getContentHash, item.getContentHash())
                            .and(w -> w.eq(DatasetItemEntity::getStatus, "ACTIVE")
                                    .or().isNull(DatasetItemEntity::getStatus))
                            .last("LIMIT 1"));
            if (duplicate != null) {
                item = duplicate;
            }
        }
        if (item.getId() == null) {
            item.setSourceType("SYNTHETIC");
            item.setSourceRef("synthesis:" + jobId + "/" + candidateId);
            item.setStatus("ACTIVE");
            item.setCreatedAt(LocalDateTime.now());
            datasetItemMapper.insert(item);
        }
        candidate.setStatus(DatasetSynthesisCandidateEntity.STATUS_APPROVED);
        candidate.setCreatedItemId(item.getId());
        candidate.setReviewedBy(reviewer);
        candidate.setReviewedAt(LocalDateTime.now());
        candidateMapper.updateById(candidate);
        jobMapper.update(null, Wrappers.<DatasetSynthesisJobEntity>lambdaUpdate()
                .eq(DatasetSynthesisJobEntity::getId, job.getId())
                .setSql("accepted_count = COALESCE(accepted_count, 0) + 1"));
        return item;
    }

    @Transactional
    public DatasetSynthesisCandidateEntity reject(String workspaceId, String datasetId, String jobId,
                                                  String candidateId, String reviewer, String note) {
        DatasetSynthesisJobEntity job = getJob(workspaceId, datasetId, jobId);
        DatasetSynthesisCandidateEntity candidate = requireCandidate(workspaceId, jobId, candidateId);
        if (DatasetSynthesisCandidateEntity.STATUS_REJECTED.equals(candidate.getStatus())) {
            return candidate;
        }
        if (!DatasetSynthesisCandidateEntity.STATUS_DRAFT.equals(candidate.getStatus())) {
            throw new EmonLensException(ErrorCode.CONFLICT,
                    "candidate is not DRAFT: " + candidate.getStatus());
        }
        candidate.setStatus(DatasetSynthesisCandidateEntity.STATUS_REJECTED);
        candidate.setFilterReason(note == null || note.isBlank() ? "REJECTED_BY_REVIEWER" : note);
        candidate.setReviewedBy(reviewer);
        candidate.setReviewedAt(LocalDateTime.now());
        candidateMapper.updateById(candidate);
        jobMapper.update(null, Wrappers.<DatasetSynthesisJobEntity>lambdaUpdate()
                .eq(DatasetSynthesisJobEntity::getId, job.getId())
                .setSql("rejected_count = COALESCE(rejected_count, 0) + 1"));
        return candidate;
    }

    // ==================== worker 支撑 ====================

    public DatasetSynthesisJobEntity claimNextPending() {
        List<DatasetSynthesisJobEntity> jobs = jobMapper.selectList(
                Wrappers.<DatasetSynthesisJobEntity>lambdaQuery()
                        .eq(DatasetSynthesisJobEntity::getStatus, DatasetSynthesisJobEntity.STATUS_PENDING)
                        .orderByAsc(DatasetSynthesisJobEntity::getCreatedAt)
                        .last("LIMIT 5"));
        for (DatasetSynthesisJobEntity job : jobs) {
            int updated = jobMapper.update(null, Wrappers.<DatasetSynthesisJobEntity>lambdaUpdate()
                    .eq(DatasetSynthesisJobEntity::getId, job.getId())
                    .eq(DatasetSynthesisJobEntity::getStatus, DatasetSynthesisJobEntity.STATUS_PENDING)
                    .set(DatasetSynthesisJobEntity::getStatus, DatasetSynthesisJobEntity.STATUS_RUNNING)
                    .set(DatasetSynthesisJobEntity::getStartedAt, LocalDateTime.now()));
            if (updated > 0) {
                job.setStatus(DatasetSynthesisJobEntity.STATUS_RUNNING);
                return job;
            }
        }
        return null;
    }

    public void recoverInterrupted() {
        int updated = jobMapper.update(null, Wrappers.<DatasetSynthesisJobEntity>lambdaUpdate()
                .eq(DatasetSynthesisJobEntity::getStatus, DatasetSynthesisJobEntity.STATUS_RUNNING)
                .set(DatasetSynthesisJobEntity::getStatus, DatasetSynthesisJobEntity.STATUS_INTERRUPTED)
                .set(DatasetSynthesisJobEntity::getFinishedAt, LocalDateTime.now()));
        if (updated > 0) {
            log.info("synthesis jobs marked INTERRUPTED on startup: {}", updated);
        }
    }

    @Transactional
    public void finish(DatasetSynthesisJobEntity job, String status, String error,
                       int promptTokens, int completionTokens) {
        LocalDateTime now = LocalDateTime.now();
        // 只写执行态字段：不能把 worker 内存里的旧 accepted/rejected 计数覆盖掉人工审批结果
        jobMapper.update(null, Wrappers.<DatasetSynthesisJobEntity>lambdaUpdate()
                .eq(DatasetSynthesisJobEntity::getId, job.getId())
                .set(DatasetSynthesisJobEntity::getStatus, status)
                .set(DatasetSynthesisJobEntity::getError, clip(error, 1000))
                .set(DatasetSynthesisJobEntity::getPromptTokens, promptTokens)
                .set(DatasetSynthesisJobEntity::getCompletionTokens, completionTokens)
                .set(DatasetSynthesisJobEntity::getFinishedAt, now));
        job.setStatus(status);
        job.setError(clip(error, 1000));
        job.setPromptTokens(promptTokens);
        job.setCompletionTokens(completionTokens);
        job.setFinishedAt(now);
    }

    public void updateProgress(DatasetSynthesisJobEntity job, int total, int rejected) {
        jobMapper.update(null, Wrappers.<DatasetSynthesisJobEntity>lambdaUpdate()
                .eq(DatasetSynthesisJobEntity::getId, job.getId())
                .set(DatasetSynthesisJobEntity::getTotalCandidates, total)
                .set(DatasetSynthesisJobEntity::getRejectedCount, rejected));
        job.setTotalCandidates(total);
        job.setRejectedCount(rejected);
    }

    public boolean candidateKeyExists(String jobId, String candidateKey) {
        Long count = candidateMapper.selectCount(Wrappers.<DatasetSynthesisCandidateEntity>lambdaQuery()
                .eq(DatasetSynthesisCandidateEntity::getJobId, jobId)
                .eq(DatasetSynthesisCandidateEntity::getCandidateKey, candidateKey));
        return count != null && count > 0;
    }

    public void insertCandidate(DatasetSynthesisCandidateEntity candidate) {
        try {
            candidateMapper.insert(candidate);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.debug("synthesis candidate duplicate: {}", candidate.getCandidateKey());
        }
    }

    public List<DatasetItemEntity> activeItems(String datasetId) {
        return datasetItemMapper.selectList(Wrappers.<DatasetItemEntity>lambdaQuery()
                .eq(DatasetItemEntity::getDatasetId, datasetId)
                .and(w -> w.eq(DatasetItemEntity::getStatus, "ACTIVE")
                        .or().isNull(DatasetItemEntity::getStatus)));
    }

    // ==================== payload / config ====================

    private DatasetItemEntity payloadToItem(String datasetId, Map<String, Object> payload) {
        String input = text(payload, "input");
        String expected = text(payload, "expected_output", "expectedOutput");
        String retrieval = jsonField(payload, "retrieval_context", "retrievalContext");
        String expectedTools = jsonField(payload, "expected_tools", "expectedTools");
        String turns = jsonField(payload, "turns");
        String expectations = jsonField(payload, "expectations");
        String metadata = jsonField(payload, "metadata");
        if (input == null || input.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "candidate payload missing input");
        }
        boolean hasV2 = EvaluationCaseService.hasV2Fields(
                retrieval, expectedTools, turns, expectations);
        int schemaVersion = evaluationCaseService.effectiveSchemaVersion(2, hasV2);
        evaluationCaseService.validate(schemaVersion, input, expected, metadata,
                retrieval, expectedTools, turns, expectations,
                properties.getDatasetRun().getMaxSnapshotChars());

        DatasetItemEntity item = new DatasetItemEntity();
        item.setDatasetId(datasetId);
        item.setInput(input);
        item.setExpectedOutput(expected);
        item.setMetadataJson(metadata);
        item.setSchemaVersion(schemaVersion);
        item.setRetrievalContextJson(retrieval);
        item.setExpectedToolsJson(expectedTools);
        item.setTurnsJson(turns);
        item.setExpectationsJson(expectations);
        item.setPurposeTags(text(payload, "purpose_tags", "purposeTags"));
        item.setRiskLevel(defaultIfBlank(text(payload, "risk_level", "riskLevel"), "UNASSIGNED"));
        item.setGroupKey(text(payload, "group_key", "groupKey"));
        item.setSplit(defaultIfBlank(text(payload, "split"), "UNASSIGNED"));
        item.setFailureMode(text(payload, "failure_mode", "failureMode"));
        item.setTraceId(text(payload, "trace_id", "traceId"));
        return item;
    }

    private void validateSource(String type, Map<String, Object> source) {
        if (SOURCE_DOCUMENTS.equals(type)) {
            Object documents = source.get("documents");
            if (!(documents instanceof List) || ((List<?>) documents).isEmpty()) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "DOCUMENTS source requires documents:[{name,content}]");
            }
        }
        if (SOURCE_TRACE_CLUSTER.equals(type)) {
            Object traceIds = source.get("traceIds");
            if (!(traceIds instanceof List) || ((List<?>) traceIds).isEmpty()) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "TRACE_CLUSTER source requires traceIds:[...]");
            }
        }
    }

    private Map<String, Object> effectiveConfig(Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>(config == null ? Map.of() : config);
        int max = integer(result.get("maxCandidates"),
                properties.getDatasetSynthesis().getMaxCandidates());
        max = Math.max(1, Math.min(properties.getDatasetSynthesis().getMaxCandidates(), max));
        result.put("maxCandidates", max);
        double minQuality = number(result.get("minQuality"),
                properties.getDatasetSynthesis().getMinQuality());
        result.put("minQuality", Math.max(0, Math.min(1, minQuality)));
        int retries = integer(result.get("maxRetries"),
                properties.getDatasetSynthesis().getMaxRetries());
        result.put("maxRetries", Math.max(0, Math.min(3, retries)));
        if (!(result.get("evolutions") instanceof Map)) {
            Map<String, Object> evolutions = new LinkedHashMap<>();
            for (String key : List.of("REASONING", "MULTI_CONTEXT", "CONCRETIZING",
                    "CONSTRAINED", "COMPARATIVE", "HYPOTHETICAL", "IN_BREADTH")) {
                evolutions.put(key, 1.0 / 7);
            }
            result.put("evolutions", evolutions);
        }
        return result;
    }

    private String effectiveModel(Map<String, Object> config) {
        Object model = config.get("model");
        if (model != null && !String.valueOf(model).isBlank()) {
            return String.valueOf(model);
        }
        return properties.getEval().getLlm().getModel();
    }

    private DatasetEntity requireDataset(String workspaceId, String datasetId) {
        DatasetEntity dataset = datasetMapper.selectById(datasetId);
        if (dataset == null || !workspaceId.equals(dataset.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset not found");
        }
        return dataset;
    }

    private DatasetSynthesisCandidateEntity requireCandidate(String workspaceId,
                                                             String jobId, String candidateId) {
        DatasetSynthesisCandidateEntity candidate = candidateMapper.selectById(candidateId);
        if (candidate == null || !workspaceId.equals(candidate.getWorkspaceId())
                || !jobId.equals(candidate.getJobId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "synthesis candidate not found");
        }
        return candidate;
    }

    private String jsonField(Map<String, Object> raw, String... keys) {
        Object value = first(raw, keys);
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
            throw new EmonLensException(ErrorCode.BAD_REQUEST, keys[0] + " 序列化失败");
        }
    }

    private String text(Map<String, Object> raw, String... keys) {
        Object value = first(raw, keys);
        return value == null ? null : String.valueOf(value);
    }

    private Object first(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            Object value = raw.get(key);
            if (value != null && !(value instanceof String && ((String) value).isBlank())) {
                return value;
            }
        }
        return null;
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Map<String, Object> parseMap(String json) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "candidate payload is not JSON");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "synthesis config is not JSON");
        }
    }

    /** JsonNode.equals 对对象字段顺序不敏感，适合比较同一请求的不同序列化。 */
    private boolean sameJson(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        try {
            return objectMapper.readTree(left).equals(objectMapper.readTree(right));
        } catch (Exception e) {
            return false;
        }
    }

    private String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Map<String, Object> toMap(DatasetSynthesisJobEntity job, boolean contentAllowed) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", job.getId());
        map.put("datasetId", job.getDatasetId());
        map.put("sourceType", job.getSourceType());
        if (contentAllowed) {
            map.put("source", parseMapQuiet(job.getSourceJson()));
            map.put("config", parseMapQuiet(job.getConfigJson()));
        } else {
            // source 可能含 DOCUMENTS 正文 / TRACE 预览；无内容权限时只给结构摘要
            Map<String, Object> source = parseMapQuiet(job.getSourceJson());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("itemIds", sizeOf(source.get("itemIds")));
            summary.put("traceIds", sizeOf(source.get("traceIds")));
            summary.put("documents", sizeOf(source.get("documents")));
            summary.put("numGoldens", source.get("numGoldens"));
            map.put("sourceSummary", summary);
            Map<String, Object> config = parseMapQuiet(job.getConfigJson());
            Map<String, Object> configSummary = new LinkedHashMap<>();
            configSummary.put("maxCandidates", config.get("maxCandidates"));
            configSummary.put("minQuality", config.get("minQuality"));
            configSummary.put("maxRetries", config.get("maxRetries"));
            configSummary.put("evolutions", config.get("evolutions"));
            map.put("configSummary", configSummary);
        }
        map.put("status", job.getStatus());
        map.put("totalCandidates", job.getTotalCandidates());
        map.put("acceptedCount", job.getAcceptedCount());
        map.put("rejectedCount", job.getRejectedCount());
        map.put("model", job.getModel());
        map.put("promptTokens", job.getPromptTokens());
        map.put("completionTokens", job.getCompletionTokens());
        map.put("error", job.getError());
        map.put("createdBy", job.getCreatedBy());
        map.put("createdAt", job.getCreatedAt());
        map.put("startedAt", job.getStartedAt());
        map.put("finishedAt", job.getFinishedAt());
        return map;
    }

    public Map<String, Object> toMap(DatasetSynthesisCandidateEntity candidate, boolean contentAllowed) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", candidate.getId());
        map.put("jobId", candidate.getJobId());
        map.put("candidateKey", candidate.getCandidateKey());
        map.put("evolution", candidate.getEvolution());
        String sourceRef = candidate.getSourceRef();
        if (!contentAllowed && sourceRef != null && sourceRef.startsWith("doc:")) {
            // 文档名可能本身含敏感信息；无内容权限时只保留来源类型
            sourceRef = "doc:***";
        }
        map.put("sourceRef", sourceRef);
        map.put("qualityScore", candidate.getQualityScore());
        String filterReason = candidate.getFilterReason();
        if (!contentAllowed && filterReason != null) {
            // critic/人工备注可能引用样本内容；无内容权限时只保留原因代码
            int colon = filterReason.indexOf(':');
            filterReason = colon > 0 ? filterReason.substring(0, colon) : filterReason;
        }
        map.put("filterReason", filterReason);
        map.put("status", candidate.getStatus());
        map.put("createdItemId", candidate.getCreatedItemId());
        map.put("reviewedBy", candidate.getReviewedBy());
        map.put("reviewedAt", candidate.getReviewedAt());
        map.put("createdAt", candidate.getCreatedAt());
        if (contentAllowed) {
            map.put("payload", parseMapQuiet(candidate.getPayloadJson()));
        }
        return map;
    }

    private int sizeOf(Object value) {
        return value instanceof List ? ((List<?>) value).size() : 0;
    }

    private Map<String, Object> parseMapQuiet(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return map == null ? Map.of() : map;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
