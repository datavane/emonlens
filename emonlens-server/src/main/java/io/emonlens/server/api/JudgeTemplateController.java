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
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.engine.eval.JudgeTemplateService;
import io.emonlens.server.storage.entity.JudgeTemplateEntity;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * Judge 模板查询与 workspace 自定义模板版本化。
 * 平台模板只读；workspace 模板按版本追加，不允许原地修改。
 */
@RestController
@RequestMapping("/api/v1/judge-templates")
@RequiredArgsConstructor
public class JudgeTemplateController {

    private final JudgeTemplateService judgeTemplateService;

    @Data
    public static class CreateTemplateRequest {
        private String templateKey;
        private String name;
        private String systemPrompt;
        private String userPrompt;
        private String outputSchema;
    }

    @GetMapping
    @RequiresPermission(Permissions.EVALUATOR_QUERY)
    public ApiResult<List<Map<String, Object>>> list(
            @RequestParam(required = false) String key) {
        return ApiResult.ok(judgeTemplateService.list(
                CurrentContext.require().getWorkspaceId(), key));
    }

    @GetMapping("/versions")
    @RequiresPermission(Permissions.EVALUATOR_QUERY)
    public ApiResult<List<Map<String, Object>>> versions(
            @RequestParam(required = false) String key) {
        return ApiResult.ok(judgeTemplateService.list(
                CurrentContext.require().getWorkspaceId(), key));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.EVALUATOR_QUERY)
    public ApiResult<Map<String, Object>> get(@PathVariable String id) {
        JudgeTemplateEntity entity = judgeTemplateService.get(
                CurrentContext.require().getWorkspaceId(), id);
        return ApiResult.ok(judgeTemplateService.toMap(entity));
    }

    @PostMapping
    @RequiresPermission(Permissions.EVALUATOR_MANAGE)
    @Audit(action = "judge_template.create", resourceType = "judge_template")
    public ApiResult<Map<String, Object>> create(@RequestBody CreateTemplateRequest request) {
        JudgeTemplateEntity entity = judgeTemplateService.create(
                CurrentContext.require().getWorkspaceId(),
                request.getTemplateKey(),
                request.getName(),
                request.getSystemPrompt(),
                request.getUserPrompt(),
                request.getOutputSchema(),
                CurrentContext.require().getUserId());
        return ApiResult.ok(judgeTemplateService.toMap(entity));
    }

    @PostMapping("/{id}/deprecate")
    @RequiresPermission(Permissions.EVALUATOR_MANAGE)
    @Audit(action = "judge_template.deprecate", resourceType = "judge_template")
    public ApiResult<Map<String, Object>> deprecate(@PathVariable String id) {
        JudgeTemplateEntity entity = judgeTemplateService.deprecate(
                CurrentContext.require().getWorkspaceId(), id);
        return ApiResult.ok(judgeTemplateService.toMap(entity));
    }
}
