package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.api.QualityCaseController.CreateRequest;
import io.emonlens.server.api.QualityCaseController.PatchRequest;
import io.emonlens.server.engine.gate.QualityGateService;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.QualityCaseEntity;
import io.emonlens.server.storage.entity.QualityGateEntity;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;
import io.emonlens.server.storage.mapper.QualityCaseMapper;
import io.emonlens.server.storage.mapper.QualityGateMapper;

import lombok.RequiredArgsConstructor;

/**
 * 质量案例闭环：发现 -> 根因/修复 -> 回归 run -> Gate PASS -> 关闭。
 * 关闭必须消费回归 run 的最新 PASS Gate 且 verification.usable=true，不能只填一个 runId。
 */
@Service
@RequiredArgsConstructor
public class QualityCaseService {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_INVESTIGATING = "INVESTIGATING";
    public static final String STATUS_FIXED = "FIXED";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_REJECTED = "REJECTED";

    private static final Set<String> STATUSES = Set.of(
            STATUS_OPEN, STATUS_INVESTIGATING, STATUS_FIXED, STATUS_CLOSED, STATUS_REJECTED);
    private static final Set<String> ROOT_CAUSES = Set.of(
            "PROMPT", "RETRIEVAL", "TOOL", "MODEL", "DATA", "TEST", "ENVIRONMENT", "UNKNOWN", "OTHER");

    private final QualityCaseMapper caseMapper;
    private final DatasetRunMapper runMapper;
    private final DatasetRunItemMapper runItemMapper;
    private final DatasetMapper datasetMapper;
    private final QualityGateMapper gateMapper;
    private final QualityGateService gateService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Object> create(String workspaceId, CreateRequest request) {
        if (request == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "request is required");
        }
        String title = required(request.getTitle(), "title", 300);
        String phenomenon = required(request.getPhenomenon(), "phenomenon", 5000);
        String datasetId = trimToNull(request.getDatasetId());
        String sourceRunId = trimToNull(request.getSourceRunId());
        if (datasetId != null) {
            DatasetEntity dataset = datasetMapper.selectById(datasetId);
            if (dataset == null || !Objects.equals(dataset.getWorkspaceId(), workspaceId)) {
                throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset not found");
            }
        }
        if (sourceRunId != null) {
            DatasetRunEntity run = requireRun(workspaceId, sourceRunId);
            if (datasetId != null && !Objects.equals(datasetId, run.getDatasetId())) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "sourceRun 不属于该 dataset");
            }
            datasetId = run.getDatasetId();
        }
        String sourceItemId = trimToNull(request.getSourceItemId());
        if (sourceItemId != null) {
            if (sourceRunId == null) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "sourceItemId 必须与 sourceRunId 一起提交");
            }
            DatasetRunItemEntity runItem = runItemMapper.selectById(sourceItemId);
            if (runItem == null || !Objects.equals(runItem.getRunId(), sourceRunId)) {
                throw new EmonLensException(ErrorCode.NOT_FOUND, "source item not found");
            }
        }
        String rootCause = normalizeRootCause(request.getRootCauseCategory(), false);
        QualityCaseEntity entity = new QualityCaseEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setDatasetId(datasetId);
        entity.setSourceRunId(sourceRunId);
        entity.setSourceItemId(sourceItemId);
        entity.setAgentKey(trimToNull(request.getAgentKey()));
        entity.setTitle(title);
        entity.setPhenomenon(phenomenon);
        entity.setEvidenceJson(toJson(request.getEvidence() == null ? Map.of() : request.getEvidence()));
        entity.setRootCauseCategory(rootCause);
        entity.setOwner(trimToNull(request.getOwner()));
        entity.setStatus(STATUS_OPEN);
        entity.setCreatedBy(request.getCreatedBy());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        caseMapper.insert(entity);
        return detail(workspaceId, entity.getId());
    }

    @Transactional
    public Map<String, Object> patch(String workspaceId, String caseId, PatchRequest request) {
        QualityCaseEntity entity = requireCase(workspaceId, caseId);
        if (request == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "request is required");
        }
        String target = trimToNull(request.getStatus());
        if (target != null) {
            target = target.toUpperCase();
            if (!STATUSES.contains(target)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "未知案例状态: " + target);
            }
            if (STATUS_CLOSED.equals(target)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "关闭必须走 /quality-cases/{id}/close，并校验回归 Gate");
            }
            transition(entity.getStatus(), target);
            if (STATUS_FIXED.equals(target)) {
                String fingerprint = trimToNull(request.getFixFingerprint());
                if (fingerprint == null && trimToNull(entity.getFixFingerprint()) == null) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST,
                            "进入 FIXED 必须填写 fixFingerprint");
                }
                if (fingerprint != null) {
                    entity.setFixFingerprint(fingerprint);
                }
            }
            entity.setStatus(target);
        }
        if (request.getRootCauseCategory() != null) {
            entity.setRootCauseCategory(normalizeRootCause(request.getRootCauseCategory(), true));
        }
        if (request.getOwner() != null) {
            entity.setOwner(trimToNull(request.getOwner()));
        }
        if (request.getFixFingerprint() != null) {
            entity.setFixFingerprint(trimToNull(request.getFixFingerprint()));
        }
        if (request.getFixNote() != null) {
            entity.setFixNote(trimToNull(request.getFixNote()));
        }
        entity.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(entity);
        return detail(workspaceId, caseId);
    }

    @Transactional
    public Map<String, Object> linkRegression(String workspaceId, String caseId, String runId) {
        QualityCaseEntity entity = requireCase(workspaceId, caseId);
        if (STATUS_CLOSED.equals(entity.getStatus()) || STATUS_REJECTED.equals(entity.getStatus())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "终态案例不能再关联回归 run");
        }
        DatasetRunEntity run = requireRun(workspaceId, trimToNull(runId));
        if (entity.getDatasetId() != null && !Objects.equals(entity.getDatasetId(), run.getDatasetId())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "回归 run 不属于案例 dataset");
        }
        if (!DatasetRunEntity.STATUS_COMPLETED.equals(run.getStatus())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "回归 run 必须 COMPLETED");
        }
        entity.setRegressionRunId(run.getId());
        entity.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(entity);
        return detail(workspaceId, caseId);
    }

    @Transactional
    public Map<String, Object> close(String workspaceId, String caseId, String verifiedBy) {
        QualityCaseEntity entity = requireCase(workspaceId, caseId);
        if (STATUS_CLOSED.equals(entity.getStatus())) {
            return detail(workspaceId, caseId);
        }
        if (!STATUS_FIXED.equals(entity.getStatus())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "只有 FIXED 案例可以关闭");
        }
        if (trimToNull(entity.getFixFingerprint()) == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "关闭前必须记录 fixFingerprint");
        }
        String regressionRunId = trimToNull(entity.getRegressionRunId());
        if (regressionRunId == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "关闭前必须关联回归 run");
        }
        DatasetRunEntity run = requireRun(workspaceId, regressionRunId);
        if (!DatasetRunEntity.STATUS_COMPLETED.equals(run.getStatus())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "回归 run 必须 COMPLETED");
        }
        QualityGateEntity gate = latestGate(workspaceId, regressionRunId);
        if (gate == null || !QualityGateEntity.DECISION_PASS.equals(gate.getDecision())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "回归 run 缺 PASS Gate，不能关闭");
        }
        Map<String, Object> verification = gateService.verify(workspaceId, gate.getId());
        if (!Boolean.TRUE.equals(verification.get("usable"))) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "回归 Gate 已失效，不能关闭: " + verification.get("reasons"));
        }
        entity.setStatus(STATUS_CLOSED);
        entity.setVerifiedBy(verifiedBy);
        entity.setVerifiedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        caseMapper.updateById(entity);
        return detail(workspaceId, caseId);
    }

    public List<Map<String, Object>> list(String workspaceId, String status, String runId, String owner) {
        List<QualityCaseEntity> rows = caseMapper.selectList(
                Wrappers.<QualityCaseEntity>lambdaQuery()
                        .eq(QualityCaseEntity::getWorkspaceId, workspaceId)
                        .eq(trimToNull(status) != null, QualityCaseEntity::getStatus,
                                trimToNull(status) == null ? null : status.trim().toUpperCase())
                        .eq(trimToNull(runId) != null, QualityCaseEntity::getSourceRunId, trimToNull(runId))
                        .eq(trimToNull(owner) != null, QualityCaseEntity::getOwner, trimToNull(owner))
                        .orderByDesc(QualityCaseEntity::getCreatedAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (QualityCaseEntity row : rows) {
            result.add(toMap(row));
        }
        return result;
    }

    public Map<String, Object> detail(String workspaceId, String caseId) {
        QualityCaseEntity entity = requireCase(workspaceId, caseId);
        Map<String, Object> map = toMap(entity);
        map.put("evidence", parseMap(entity.getEvidenceJson()));
        if (entity.getRegressionRunId() != null) {
            DatasetRunEntity run = runMapper.selectById(entity.getRegressionRunId());
            map.put("regressionRunStatus", run == null ? null : run.getStatus());
            QualityGateEntity gate = latestGate(workspaceId, entity.getRegressionRunId());
            if (gate == null) {
                map.put("regressionGate", null);
            } else {
                Map<String, Object> gateMap = new LinkedHashMap<>();
                gateMap.put("id", gate.getId());
                gateMap.put("decision", gate.getDecision());
                gateMap.put("validUntil", gate.getValidUntil());
                map.put("regressionGate", gateMap);
            }
        }
        return map;
    }

    private QualityCaseEntity requireCase(String workspaceId, String caseId) {
        QualityCaseEntity entity = caseMapper.selectById(caseId);
        if (entity == null || !Objects.equals(entity.getWorkspaceId(), workspaceId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "quality case not found");
        }
        return entity;
    }

    private DatasetRunEntity requireRun(String workspaceId, String runId) {
        if (runId == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "runId is required");
        }
        DatasetRunEntity run = runMapper.selectById(runId);
        if (run == null || !Objects.equals(run.getWorkspaceId(), workspaceId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "run not found");
        }
        return run;
    }

    private QualityGateEntity latestGate(String workspaceId, String runId) {
        return gateMapper.selectOne(Wrappers.<QualityGateEntity>lambdaQuery()
                .eq(QualityGateEntity::getWorkspaceId, workspaceId)
                .eq(QualityGateEntity::getRunId, runId)
                .orderByDesc(QualityGateEntity::getCreatedAt)
                .last("LIMIT 1"));
    }

    private void transition(String current, String target) {
        if (Objects.equals(current, target)) {
            return;
        }
        boolean allowed;
        if (STATUS_OPEN.equals(current)) {
            allowed = STATUS_INVESTIGATING.equals(target) || STATUS_REJECTED.equals(target);
        } else if (STATUS_INVESTIGATING.equals(current)) {
            allowed = STATUS_FIXED.equals(target) || STATUS_REJECTED.equals(target);
        } else if (STATUS_FIXED.equals(current)) {
            allowed = STATUS_INVESTIGATING.equals(target) || STATUS_REJECTED.equals(target);
        } else {
            allowed = false;
        }
        if (!allowed) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "非法案例状态迁移: " + current + " -> " + target);
        }
    }

    private String normalizeRootCause(String value, boolean allowNull) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            if (allowNull) {
                return null;
            }
            return "UNKNOWN";
        }
        normalized = normalized.toUpperCase();
        if (!ROOT_CAUSES.contains(normalized)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "未知根因分类: " + normalized);
        }
        return normalized;
    }

    private Map<String, Object> toMap(QualityCaseEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("datasetId", e.getDatasetId());
        map.put("sourceRunId", e.getSourceRunId());
        map.put("sourceItemId", e.getSourceItemId());
        map.put("agentKey", e.getAgentKey());
        map.put("title", e.getTitle());
        map.put("phenomenon", e.getPhenomenon());
        map.put("rootCauseCategory", e.getRootCauseCategory());
        map.put("owner", e.getOwner());
        map.put("status", e.getStatus());
        map.put("fixFingerprint", e.getFixFingerprint());
        map.put("fixNote", e.getFixNote());
        map.put("regressionRunId", e.getRegressionRunId());
        map.put("verifiedBy", e.getVerifiedBy());
        map.put("verifiedAt", e.getVerifiedAt());
        map.put("createdBy", e.getCreatedBy());
        map.put("createdAt", e.getCreatedAt());
        map.put("updatedAt", e.getUpdatedAt());
        return map;
    }

    private String required(String value, String field, int maxLength) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " is required");
        }
        if (trimmed.length() > maxLength) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    field + " 超过最大长度 " + maxLength);
        }
        return trimmed;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "case payload 无法序列化");
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
}
