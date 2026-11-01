package io.emonlens.server.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.engine.eval.QualityCaseService;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/** 质量问题闭环：登记、修复、回归验证、关闭。 */
@RestController
@RequestMapping("/api/v1/quality-cases")
@RequiredArgsConstructor
public class QualityCaseController {

    private final QualityCaseService qualityCaseService;

    @Data
    public static class CreateRequest {
        private String datasetId;
        private String sourceRunId;
        private String sourceItemId;
        private String agentKey;
        private String title;
        private String phenomenon;
        private Map<String, Object> evidence;
        private String rootCauseCategory;
        private String owner;
        private String createdBy;
    }

    @Data
    public static class PatchRequest {
        private String status;
        private String rootCauseCategory;
        private String owner;
        private String fixFingerprint;
        private String fixNote;
    }

    @Data
    public static class RegressionRequest {
        private String runId;
    }

    @PostMapping
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "quality.case.create", resourceType = "quality_case")
    public ApiResult<Map<String, Object>> create(@RequestBody CreateRequest request) {
        request.setCreatedBy(CurrentContext.require().getUserId());
        return ApiResult.ok(qualityCaseService.create(
                CurrentContext.require().getWorkspaceId(), request));
    }

    @GetMapping
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String owner) {
        return ApiResult.ok(qualityCaseService.list(
                CurrentContext.require().getWorkspaceId(), status, runId, owner));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        return ApiResult.ok(qualityCaseService.detail(
                CurrentContext.require().getWorkspaceId(), id));
    }

    @PatchMapping("/{id}")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "quality.case.update", resourceType = "quality_case")
    public ApiResult<Map<String, Object>> patch(@PathVariable String id,
                                                @RequestBody PatchRequest request) {
        return ApiResult.ok(qualityCaseService.patch(
                CurrentContext.require().getWorkspaceId(), id, request));
    }

    @PostMapping("/{id}/regression")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "quality.case.regression", resourceType = "quality_case")
    public ApiResult<Map<String, Object>> linkRegression(@PathVariable String id,
                                                         @RequestBody RegressionRequest request) {
        return ApiResult.ok(qualityCaseService.linkRegression(
                CurrentContext.require().getWorkspaceId(), id,
                request == null ? null : request.getRunId()));
    }

    @PostMapping("/{id}/close")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "quality.case.close", resourceType = "quality_case")
    public ApiResult<Map<String, Object>> close(@PathVariable String id) {
        return ApiResult.ok(qualityCaseService.close(
                CurrentContext.require().getWorkspaceId(), id,
                CurrentContext.require().getUserId()));
    }
}
