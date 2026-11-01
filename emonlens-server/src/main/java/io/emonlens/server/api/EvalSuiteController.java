package io.emonlens.server.api;

import java.util.List;

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
import io.emonlens.server.engine.eval.EvalSuiteService;
import io.emonlens.server.storage.entity.EvalSuiteEntity;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/eval-suites")
@RequiredArgsConstructor
public class EvalSuiteController {

    private final EvalSuiteService suiteService;

    @Data
    public static class CreateSuiteRequest {
        private String suiteKey;
        private String name;
        private String description;
        private List<String> evaluatorIds;
    }

    @PostMapping
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "eval.suite.create", resourceType = "eval_suite")
    public ApiResult<EvalSuiteEntity> create(@RequestBody CreateSuiteRequest request) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        return ApiResult.ok(suiteService.create(workspaceId, request.getSuiteKey(), request.getName(),
                request.getDescription(), request.getEvaluatorIds()));
    }

    @GetMapping
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<List<EvalSuiteEntity>> list() {
        return ApiResult.ok(suiteService.list(CurrentContext.require().getWorkspaceId()));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<EvalSuiteEntity> detail(@PathVariable String id) {
        return ApiResult.ok(suiteService.requireOwned(CurrentContext.require().getWorkspaceId(), id));
    }
}
