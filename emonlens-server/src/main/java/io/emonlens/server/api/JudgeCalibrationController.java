package io.emonlens.server.api;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.annotation.Resource;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.engine.eval.JudgeCalibrationService;

/** LLM Judge 人工盲审校准；只看样本与 judge 结论，不让模型自评自证。 */
@RestController
@RequestMapping("/api/v1/datasets")
public class JudgeCalibrationController {

    @Resource
    private JudgeCalibrationService judgeCalibrationService;

    @GetMapping("/{id}/runs/{runId}/judge-calibration")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<Map<String, Object>> calibration(
            @PathVariable String id,
            @PathVariable String runId,
            @RequestParam(required = false) String evaluatorKey) {
        var ctx = CurrentContext.require();
        boolean contentAllowed = ctx.hasPermission(Permissions.TRACE_CONTENT);
        return ApiResult.ok(judgeCalibrationService.summary(
                ctx.getWorkspaceId(), id, runId, evaluatorKey, contentAllowed));
    }

    @GetMapping(value = "/{id}/runs/{runId}/judge-calibration/export",
            produces = "text/csv;charset=UTF-8")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.judge.export", resourceType = "dataset")
    public ResponseEntity<String> export(
            @PathVariable String id,
            @PathVariable String runId,
            @RequestParam(required = false) String evaluatorKey) {
        var ctx = CurrentContext.require();
        boolean contentAllowed = ctx.hasPermission(Permissions.TRACE_CONTENT);
        String csv = judgeCalibrationService.exportCsv(
                ctx.getWorkspaceId(), id, runId, evaluatorKey, contentAllowed);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"judge-calibration-" + runId + ".csv\"");
        return new ResponseEntity<>(csv, headers, HttpStatus.OK);
    }

    @PutMapping("/{id}/runs/{runId}/judge-calibration/{itemId}/adjudication")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.judge.adjudicate", resourceType = "dataset")
    public ApiResult<Map<String, Object>> adjudicate(
            @PathVariable String id,
            @PathVariable String runId,
            @PathVariable String itemId,
            @RequestBody Map<String, Object> body) {
        var ctx = CurrentContext.require();
        return ApiResult.ok(judgeCalibrationService.adjudicate(
                ctx.getWorkspaceId(), id, runId, itemId,
                body.get("evaluatorKey") == null ? null : String.valueOf(body.get("evaluatorKey")),
                body.get("humanLabel") == null ? null : String.valueOf(body.get("humanLabel")),
                body.get("note") == null ? null : String.valueOf(body.get("note"))));
    }

    @PutMapping("/{id}/runs/{runId}/judge-calibration/{itemId}")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.judge.review", resourceType = "dataset")
    public ApiResult<Map<String, Object>> review(
            @PathVariable String id,
            @PathVariable String runId,
            @PathVariable String itemId,
            @RequestBody Map<String, Object> body) {
        var ctx = CurrentContext.require();
        return ApiResult.ok(judgeCalibrationService.review(
                ctx.getWorkspaceId(), id, runId, itemId,
                body.get("evaluatorKey") == null ? null : String.valueOf(body.get("evaluatorKey")),
                body.get("humanLabel") == null ? null : String.valueOf(body.get("humanLabel")),
                body.get("note") == null ? null : String.valueOf(body.get("note"))));
    }
}
