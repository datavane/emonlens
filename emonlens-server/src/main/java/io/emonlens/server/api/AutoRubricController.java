package io.emonlens.server.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.engine.eval.AutoRubricService;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AutoRubricController {

    private final AutoRubricService autoRubricService;

    @Data
    public static class CandidateRule {
        private String key;
        private String text;
        private Double minPrecision;
        private Integer minSupport;
        private String origin;
    }

    @Data
    public static class CreateRequest {
        private String sourceRunId;
        private String judgeModel;
        private Integer maxJudgeCalls;
        private Boolean generate;
        private Integer maxCandidates;
        private String generationModel;
        private Integer bootstrapRounds;
        private Double minSelectionFrequency;
        /** K 折贪心选择折数；0 关闭，开启时 2..5。 */
        private Integer cvFolds;
        /** 生成候选是否调用 LLM 做语义重复判断；默认 false。 */
        private Boolean semanticDedup;
        private List<CandidateRule> candidates;
        private String createdBy;
    }

    @Data
    public static class DecisionRequest {
        private String decision;
        private String note;
    }

    @PostMapping("/datasets/{datasetId}/auto-rubric-runs")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.auto_rubric.create", resourceType = "auto_rubric_run")
    public ApiResult<Map<String, Object>> create(@PathVariable String datasetId,
                                                 @RequestBody CreateRequest request) {
        request.setCreatedBy(CurrentContext.require().getUserId());
        return ApiResult.ok(autoRubricService.create(
                CurrentContext.require().getWorkspaceId(), datasetId, request));
    }

    @GetMapping("/datasets/{datasetId}/auto-rubric-runs")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<List<Map<String, Object>>> list(@PathVariable String datasetId) {
        return ApiResult.ok(autoRubricService.list(
                CurrentContext.require().getWorkspaceId(), datasetId));
    }

    @GetMapping("/auto-rubric-runs/{id}")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        return ApiResult.ok(autoRubricService.detail(
                CurrentContext.require().getWorkspaceId(), id));
    }

    @PostMapping("/auto-rubric-runs/{id}/candidates/{candidateId}/decision")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.auto_rubric.decide", resourceType = "auto_rubric_candidate")
    public ApiResult<Map<String, Object>> decide(@PathVariable String id,
                                                 @PathVariable String candidateId,
                                                 @RequestBody DecisionRequest request) {
        return ApiResult.ok(autoRubricService.decide(
                CurrentContext.require().getWorkspaceId(), id, candidateId, request,
                CurrentContext.require().getUserId()));
    }
}
