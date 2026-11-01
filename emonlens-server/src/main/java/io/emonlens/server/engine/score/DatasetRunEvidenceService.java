package io.emonlens.server.engine.score;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 只读证据适配器：把已完成的 dataset run 逐评估器判定接入 Trust Score。
 *
 * <p>数据集 run 的逐样本结果是新链路事实源，不复制写回 {@code et_eval_result}。这里只读取
 * COMPLETED run 且 agent 归属匹配的 item，只接受 PASS/FAIL；缺失、损坏或未完成的证据一律跳过，
 * 由评分侧继续 fail-closed。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetRunEvidenceService {

    private final DatasetRunItemMapper datasetRunItemMapper;
    private final ObjectMapper objectMapper;

    /**
     * 读取窗口内最近完成的数据集证据。查询已按 workspace + agent 在 SQL 侧收敛，
     * 同一个 evaluator+trace 取最新 run 完成时间对应的结果。
     */
    public List<EvalResultEntity> latestEvaluations(String workspaceId, String agentKey, LocalDateTime since) {
        if (workspaceId == null || agentKey == null || since == null) {
            return List.of();
        }
        List<DatasetRunItemEntity> items = datasetRunItemMapper.selectCompletedEvidence(
                workspaceId, agentKey, since);
        if (items.isEmpty()) {
            return List.of();
        }
        Map<String, EvalResultEntity> latest = new LinkedHashMap<>();
        for (DatasetRunItemEntity item : items) {
            try {
                for (EvalResultEntity result : evidenceOf(workspaceId, item, agentKey)) {
                    latest.putIfAbsent(key(result), result);
                }
            } catch (Exception e) {
                // 单个坏 item 不能吞掉同一 agent 的其他证据。
                log.warn("dataset evidence item skipped, run={}, item={}: {}",
                        item.getRunId(), item.getId(), e.getMessage());
            }
        }
        if (!latest.isEmpty()) {
            log.debug("dataset evidence loaded, agent={}, count={}", agentKey, latest.size());
        }
        return new ArrayList<>(latest.values());
    }

    private List<EvalResultEntity> evidenceOf(String workspaceId, DatasetRunItemEntity item, String agentKey) {
        if (item.getTraceId() == null || item.getTraceId().isBlank()
                || item.getRunFinishedAt() == null) {
            return List.of();
        }
        String itemAgent = item.getAgentKey();
        if (itemAgent != null && !itemAgent.isBlank() && !agentKey.equals(itemAgent)) {
            return List.of();
        }
        Map<String, Object> snapshot = parseMap(item.getSampleSnapshotJson());
        // agent_key 为空的历史 item 用 snapshot 二次过滤；新数据以 column 为准。
        if (itemAgent == null || itemAgent.isBlank()) {
            if (snapshot == null || !agentKey.equals(asString(snapshot.get("agentKey")))) {
                return List.of();
            }
        }
        List<Object> results = parseList(item.getEvaluatorResultsJson());
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        String agentVersion = item.getAgentVersion();
        if (agentVersion == null && snapshot != null) {
            agentVersion = asString(snapshot.get("agentVersion"));
        }
        List<EvalResultEntity> evidence = new ArrayList<>();
        for (Object entry : results) {
            if (!(entry instanceof Map)) {
                continue;
            }
            Map<?, ?> raw = (Map<?, ?>) entry;
            String label = asString(raw.get("label"));
            if (!EvaluatorEntity.LABEL_PASS.equals(label) && !EvaluatorEntity.LABEL_FAIL.equals(label)) {
                continue;
            }
            String evaluatorId = asString(raw.get("evaluatorId"));
            String evaluatorKey = asString(raw.get("evaluatorKey"));
            if (evaluatorId == null || evaluatorId.isBlank() || evaluatorKey == null || evaluatorKey.isBlank()) {
                continue;
            }
            EvalResultEntity result = new EvalResultEntity();
            result.setWorkspaceId(workspaceId);
            result.setEvaluatorId(evaluatorId);
            result.setEvaluatorKey(evaluatorKey);
            result.setEvaluatorVersion(asInteger(raw.get("evaluatorVersion")));
            result.setAgentKey(agentKey);
            result.setAgentVersion(agentVersion);
            result.setTraceId(item.getTraceId());
            result.setAttempt(1);
            result.setLabel(label);
            result.setScore(asDecimal(raw.get("score")));
            result.setReason(truncate(asString(raw.get("reason")), 1000));
            result.setCreatedAt(item.getRunFinishedAt());
            evidence.add(result);
        }
        return evidence;
    }

    private String key(EvalResultEntity result) {
        // 统一以 evaluatorId 配对；TrustScoreService 的 legacy 去重也以 id 优先。
        return result.getEvaluatorId() + "|" + result.getTraceId();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("dataset evidence snapshot parse failed: {}", e.getMessage());
            return null;
        }
    }

    private List<Object> parseList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("dataset evidence evaluator results parse failed: {}", e.getMessage());
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private BigDecimal asDecimal(Object value) {
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof String && !((String) value).isBlank()) {
            try {
                return new BigDecimal((String) value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
