package io.emonlens.server.openapi;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.audit.AuditService;
import io.emonlens.server.engine.monitor.MonitorService;
import io.emonlens.server.engine.eval.DatasetRunService;
import io.emonlens.server.engine.gate.QualityGateService;
import io.emonlens.server.engine.score.TrustScoreService;
import io.emonlens.server.storage.entity.AccessTokenEntity;
import io.emonlens.server.storage.entity.MonitorEntity;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.QualityGateEntity;
import io.emonlens.server.storage.entity.TrustScoreEntity;
import io.emonlens.server.storage.mapper.AccessTokenMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * 开放 API（X-Api-Token）：外部系统（CI/CD、ITSM、数据质量平台）机器对机器调用。
 * token 属于签发时的工作空间，全部数据访问按该工作空间隔离。
 */
@RestController
@RequestMapping("/api/v1/open")
@RequiredArgsConstructor
public class OpenApiController {

    private final AccessTokenMapper accessTokenMapper;
    private final MonitorService monitorService;
    private final TrustScoreService trustScoreService;
    private final QualityGateService qualityGateService;
    private final DatasetRunService datasetRunService;
    private final AuditService auditService;

    private AccessTokenEntity verify(String token, String scope) {
        if (token == null || token.isEmpty()) {
            throw new EmonLensException(ErrorCode.UNAUTHORIZED, "missing X-Api-Token");
        }
        AccessTokenEntity entity = accessTokenMapper.selectOne(Wrappers.<AccessTokenEntity>lambdaQuery()
                .eq(AccessTokenEntity::getTokenHash, Sha256Utils.hash(token))
                .eq(AccessTokenEntity::getStatus, AccessTokenEntity.STATUS_ACTIVE));
        if (entity == null) {
            throw new EmonLensException(ErrorCode.UNAUTHORIZED, "invalid api token");
        }
        // 兼容历史签发的 scope 串：trim 去空格，避免 "score:read, monitors:write" 第二个 scope 误判 403
        Set<String> scopes = new HashSet<>();
        for (String s : entity.getScopes().split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                scopes.add(trimmed);
            }
        }
        if (!scopes.contains(scope)) {
            throw new EmonLensException(ErrorCode.FORBIDDEN, "token missing scope: " + scope);
        }
        entity.setLastUsedAt(LocalDateTime.now());
        accessTokenMapper.updateById(entity);
        return entity;
    }

    @GetMapping("/score/agents/{agentKey}")
    public ApiResult<TrustScoreEntity> agentScore(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @PathVariable String agentKey) {
        AccessTokenEntity tokenEntity = verify(token, "score:read");
        TrustScoreEntity snapshot = trustScoreService.latest(tokenEntity.getWorkspaceId(), agentKey, "24h");
        if (snapshot == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "no score snapshot for agent: " + agentKey);
        }
        return ApiResult.ok(snapshot);
    }

    /** CI 生成准入结论；只应消费 PASS，BLOCKED 不得放行。 */
    @PostMapping("/quality-gates")
    public ApiResult<QualityGateEntity> createQualityGate(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @RequestBody OpenGateBody body) {
        AccessTokenEntity tokenEntity = verify(token, "score:read");
        if (body.getRunId() == null || body.getRunId().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "runId is required");
        }
        QualityGateEntity gate = qualityGateService.evaluate(tokenEntity.getWorkspaceId(), body.getRunId(),
                body.getPolicyKey(), body.getMinCoverage() == null ? 90.0 : body.getMinCoverage(),
                body.getMaxFail() == null ? 0 : body.getMaxFail(),
                body.getMaxUnknown() == null ? 0 : body.getMaxUnknown(),
                body.getValidHours() == null ? 24 : body.getValidHours(), "open-api");
        auditService.recordSystem(tokenEntity.getWorkspaceId(),
                "open-api:" + tokenEntity.getName(), "open.quality_gate.evaluate", "quality_gate", gate.getId(),
                Map.of("runId", body.getRunId(), "policyKey", String.valueOf(body.getPolicyKey()),
                        "decision", String.valueOf(gate.getDecision()),
                        "coverage", String.valueOf(gate.getCoverage())));
        return ApiResult.ok(gate);
    }

    /** CI 校验某个历史 Gate 是否仍可用：过期、被取代、run 变更或指纹变化都返回 usable=false。 */
    @GetMapping("/quality-gates/{id}/verification")
    public ApiResult<Map<String, Object>> verifyQualityGate(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @PathVariable String id) {
        AccessTokenEntity tokenEntity = verify(token, "score:read");
        return ApiResult.ok(qualityGateService.verify(tokenEntity.getWorkspaceId(), id));
    }

    /** CI 读取某次 run 的最新准入结论 */
    @GetMapping("/quality-gates/{runId}")
    public ApiResult<QualityGateEntity> latestQualityGate(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @PathVariable String runId) {
        AccessTokenEntity tokenEntity = verify(token, "score:read");
        QualityGateEntity gate = qualityGateService.latest(tokenEntity.getWorkspaceId(), runId);
        if (gate == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "no quality gate for run: " + runId);
        }
        return ApiResult.ok(gate);
    }

    @Data
    public static class OpenRunBody {
        private String datasetId;
        private String evaluatorId;
        private List<String> evaluatorIds;
        private String suiteId;
        private String revisionId;
        private String mode;
        private String systemPrompt;
        private String model;
        private String candidateLabel;
        private String candidateFingerprint;
        private Integer maxExternalCalls;
        private Long maxTokens;
        private String reviewMode;
    }

    /** SDK/CI 提交一次数据集评测；只返回 run 标识与状态，不返回样本正文。 */
    @PostMapping("/eval-runs")
    public ResponseEntity<ApiResult<Map<String, Object>>> createEvalRun(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OpenRunBody body) {
        AccessTokenEntity tokenEntity = verify(token, "eval:run");
        if (body == null || body.getDatasetId() == null || body.getDatasetId().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "datasetId is required");
        }
        List<String> evaluatorIds = body.getEvaluatorIds();
        if ((evaluatorIds == null || evaluatorIds.isEmpty())
                && body.getEvaluatorId() != null && !body.getEvaluatorId().isBlank()) {
            evaluatorIds = List.of(body.getEvaluatorId());
        }
        if ((evaluatorIds == null || evaluatorIds.isEmpty())
                && (body.getSuiteId() == null || body.getSuiteId().isBlank())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "evaluatorId/evaluatorIds 或 suiteId 至少提供一个");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "缺少 Idempotency-Key 请求头");
        }
        DatasetRunService.AcceptResult result = datasetRunService.accept(
                tokenEntity.getWorkspaceId(), body.getDatasetId(), evaluatorIds,
                body.getRevisionId(), body.getMode(), body.getSystemPrompt(), body.getModel(),
                body.getCandidateLabel(), body.getCandidateFingerprint(),
                body.getMaxExternalCalls(), body.getMaxTokens(), body.getReviewMode(),
                body.getSuiteId(), idempotencyKey, null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", result.getRun().getId());
        payload.put("status", result.getRun().getStatus());
        payload.put("created", result.isCreated());
        payload.put("run", result.getRun());
        auditService.recordSystem(tokenEntity.getWorkspaceId(),
                "open-api:" + tokenEntity.getName(), result.isCreated() ? "open.eval_run.create" : "open.eval_run.reuse",
                "dataset_run", result.getRun().getId(),
                Map.of("datasetId", body.getDatasetId(), "mode", String.valueOf(body.getMode()),
                        "suiteId", String.valueOf(body.getSuiteId()),
                        "evaluatorIds", body.getEvaluatorIds() == null ? List.of() : body.getEvaluatorIds(),
                        "candidateLabel", String.valueOf(body.getCandidateLabel())));
        return ResponseEntity.status(result.isCreated() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(ApiResult.ok(payload));
    }

    /** SDK/CI 查询 run 状态与最新 Gate；不返回样本正文。 */
    @GetMapping("/eval-runs/{runId}")
    public ApiResult<Map<String, Object>> getEvalRun(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @PathVariable String runId) {
        AccessTokenEntity tokenEntity = verify(token, "eval:read");
        DatasetRunEntity run = datasetRunService.requireRun(tokenEntity.getWorkspaceId(), runId);
        // CI 消费 unknown/error/incomplete 计数；不带正文，也不回传大配置快照
        datasetRunService.enrich(run, datasetRunService.itemsOf(runId), false, false);
        run.setConfigSnapshot(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("run", run);
        QualityGateEntity gate = qualityGateService.latest(tokenEntity.getWorkspaceId(), runId);
        payload.put("gate", gate);
        return ApiResult.ok(payload);
    }

    @Data
    public static class OpenGateBody {
        private String runId;
        private String policyKey;
        private Double minCoverage;
        private Integer maxFail;
        private Integer maxUnknown;
        private Integer validHours;
    }

    /** 外部系统注册规则（如 CI 流水线自动挂门禁）；workspace 随 token */
    @PostMapping("/monitors")
    public ApiResult<Map<String, Object>> createMonitor(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @RequestBody MonitorBody body) {
        AccessTokenEntity tokenEntity = verify(token, "monitors:write");
        if (body == null || isBlank(body.getAgentKey()) || isBlank(body.getMonitorKey())
                || isBlank(body.getKind())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "agentKey, monitorKey and kind are required");
        }
        if (body.getSeverity() != null && !isBlank(body.getSeverity())
                && !Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW")
                        .contains(body.getSeverity().trim().toUpperCase())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "severity must be CRITICAL, HIGH, MEDIUM or LOW");
        }
        String severity = isBlank(body.getSeverity()) ? null : body.getSeverity().trim().toUpperCase();
        MonitorEntity entity = monitorService.create(tokenEntity.getWorkspaceId(), body.getAgentKey(),
                body.getMonitorKey(), body.getKind(), body.getSpec(),
                severity, body.getDimension());
        auditService.recordSystem(tokenEntity.getWorkspaceId(),
                "open-api:" + tokenEntity.getName(), "open.monitor.create", "monitor", entity.getId(),
                Map.of("agentKey", body.getAgentKey(), "monitorKey", body.getMonitorKey(),
                        "kind", body.getKind(), "severity", severity == null ? "HIGH" : severity,
                        "dimension", entity.getDimension()));
        return ApiResult.ok(Map.of(
                "id", entity.getId(),
                "monitorKey", entity.getMonitorKey(),
                "workspaceId", entity.getWorkspaceId()));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Data
    public static class MonitorBody {
        private String agentKey;
        private String monitorKey;
        private String kind;
        private java.util.Map<String, Object> spec;
        private String severity;
        private String dimension;
    }
}
