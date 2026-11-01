package io.emonlens.server.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.engine.gate.QualityGateService;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.storage.entity.QualityGateEntity;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * 最小准入 Gate。CI 只应消费 PASS；FAIL 是业务失败；BLOCKED 表示无法判断，不能放行。
 */
@RestController
@RequestMapping("/api/v1/quality-gates")
@RequiredArgsConstructor
public class QualityGateController {

    private final QualityGateService gateService;

    @Data
    public static class CreateGateRequest {
        private String runId;
        private String policyKey;
        /** 可选：从 run 绑定的 Domain Pack gateTemplates 读取政策；不能与显式阈值同时使用。 */
        private String gateTemplateKey;
        /** 最低判断覆盖率（PASS+FAIL / 样本数），默认 90 */
        private Double minCoverage;
        /** 允许的 FAIL 数，默认 0 */
        private Integer maxFail;
        /** 允许的 UNKNOWN 数，默认 0 */
        private Integer maxUnknown;
        /** 结论有效期（小时），默认 24 */
        private Integer validHours;
    }

    @PostMapping
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "quality_gate.evaluate", resourceType = "dataset_run")
    public ApiResult<QualityGateEntity> evaluate(@RequestBody CreateGateRequest request) {
        if (request.getRunId() == null || request.getRunId().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "runId is required");
        }
        if (request.getGateTemplateKey() != null && !request.getGateTemplateKey().isBlank()
                && (request.getMinCoverage() != null || request.getMaxFail() != null
                || request.getMaxUnknown() != null || request.getValidHours() != null)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "gateTemplateKey 不能与显式 gate 阈值同时使用");
        }
        double minCoverage = request.getMinCoverage() == null ? 90.0 : request.getMinCoverage();
        int maxFail = request.getMaxFail() == null ? 0 : request.getMaxFail();
        int maxUnknown = request.getMaxUnknown() == null ? 0 : request.getMaxUnknown();
        int validHours = request.getValidHours() == null ? 24 : request.getValidHours();
        if (minCoverage < 0 || minCoverage > 100 || maxFail < 0 || maxUnknown < 0 || validHours < 1) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "invalid gate policy");
        }
        return ApiResult.ok(gateService.evaluate(CurrentContext.require().getWorkspaceId(),
                request.getRunId(), request.getPolicyKey(), minCoverage, maxFail, maxUnknown,
                validHours, CurrentContext.require().getUserId(), request.getGateTemplateKey()));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<QualityGateEntity> get(@PathVariable String id) {
        return ApiResult.ok(gateService.get(CurrentContext.require().getWorkspaceId(), id));
    }

    /** 同一 run 的 Gate 历史；旧 Gate 即使被取代也保留 verification 原因。 */
    @GetMapping("/history")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<java.util.List<Map<String, Object>>> history(@RequestParam String runId) {
        if (runId == null || runId.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "runId is required");
        }
        return ApiResult.ok(gateService.history(CurrentContext.require().getWorkspaceId(), runId));
    }

    @GetMapping("/{id}/verification")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> verify(@PathVariable String id) {
        return ApiResult.ok(gateService.verify(CurrentContext.require().getWorkspaceId(), id));
    }

    @GetMapping
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<QualityGateEntity> latest(@RequestParam String runId) {
        if (runId == null || runId.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "runId is required");
        }
        return ApiResult.ok(gateService.latest(CurrentContext.require().getWorkspaceId(), runId));
    }
}
