package io.emonlens.server.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
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
import io.emonlens.server.engine.eval.PairwiseEvaluationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/** Pairwise（D3）：只输出相对偏好，不替代绝对 Gate。 */
@RestController
@RequestMapping("/api/v1/datasets/{datasetId}/pairwise-evaluations")
@RequiredArgsConstructor
public class PairwiseEvaluationController {

    private final PairwiseEvaluationService pairwiseEvaluationService;

    @Data
    public static class HumanReviewRequest {
        private String itemId;
        private String preference;
        private String note;
    }

    @Data
    public static class CreateRequest {
        private String runAId;
        private String runBId;
        private String judgeModel;
        private List<String> judgeModels;
        private Integer samplesPerDirection;
        private Boolean positionRandomized;
        private Long positionSeed;
        private String createdBy;
    }

    @PostMapping
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.pairwise.create", resourceType = "pairwise_evaluation")
    public ApiResult<Map<String, Object>> create(@PathVariable String datasetId,
                                                 @RequestBody CreateRequest request) {
        request.setCreatedBy(CurrentContext.require().getUserId());
        return ApiResult.ok(pairwiseEvaluationService.evaluate(
                CurrentContext.require().getWorkspaceId(), datasetId, request));
    }

    @GetMapping
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<List<Map<String, Object>>> list(@PathVariable String datasetId,
                                                     @RequestParam(required = false) String runAId,
                                                     @RequestParam(required = false) String runBId) {
        return ApiResult.ok(pairwiseEvaluationService.list(
                CurrentContext.require().getWorkspaceId(), datasetId, runAId, runBId));
    }

    @PostMapping("/{evaluationId}/reviews")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.pairwise.human_review", resourceType = "pairwise_human_review")
    public ApiResult<Map<String, Object>> review(@PathVariable String datasetId,
                                                 @PathVariable String evaluationId,
                                                 @RequestBody HumanReviewRequest request) {
        return ApiResult.ok(pairwiseEvaluationService.review(
                CurrentContext.require().getWorkspaceId(), datasetId, evaluationId,
                request, CurrentContext.require().getUserId()));
    }

    @GetMapping("/{evaluationId}/calibration")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> calibration(@PathVariable String datasetId,
                                                      @PathVariable String evaluationId) {
        return ApiResult.ok(pairwiseEvaluationService.calibration(
                CurrentContext.require().getWorkspaceId(), datasetId, evaluationId));
    }

    @GetMapping("/{evaluationId}")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> detail(@PathVariable String datasetId,
                                                 @PathVariable String evaluationId) {
        return ApiResult.ok(pairwiseEvaluationService.detail(
                CurrentContext.require().getWorkspaceId(), datasetId, evaluationId));
    }
}
