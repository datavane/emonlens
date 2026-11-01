package io.emonlens.server.api;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.engine.eval.LlmClient;
import io.emonlens.server.engine.eval.LlmUnavailableException;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * Prompt Playground（对标 Langfuse Playground）：
 * 渲染 {{变量}} 模板后调用已配置的 LLM 端点（OpenAI 兼容或 Anthropic）试跑；
 * LLM 未配置时返回结构化降级态而非 500。
 */
@RestController
@RequestMapping("/api/v1/playground")
@RequiredArgsConstructor
public class PlaygroundController {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");
    /** 试跑输入上限：防止超大提示词打爆 LLM 端点（成本与延迟），正常试跑远用不到这个量级。 */
    private static final int MAX_PROMPT_CHARS = 200_000;
    private static final int MAX_VARIABLES = 200;

    private final LlmClient llmClient;

    @Data
    public static class RunRequest {
        private String systemPrompt;
        private String prompt;
        private Map<String, String> variables;
        private String model;
        private Double temperature;
    }

    @PostMapping("/run")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    public ApiResult<Map<String, Object>> run(@RequestBody RunRequest request) {
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            return ApiResult.ok(status("ERROR", "提示词内容不能为空"));
        }
        if (request.getPrompt().length() > MAX_PROMPT_CHARS
                || (request.getSystemPrompt() != null && request.getSystemPrompt().length() > MAX_PROMPT_CHARS)) {
            return ApiResult.ok(status("ERROR", "提示词过长（每段不超过 " + MAX_PROMPT_CHARS + " 字符）"));
        }
        if (request.getVariables() != null && request.getVariables().size() > MAX_VARIABLES) {
            return ApiResult.ok(status("ERROR", "变量数量过多（最多 " + MAX_VARIABLES + " 个）"));
        }
        String user = render(request.getPrompt(), request.getVariables());
        String system = request.getSystemPrompt() == null || request.getSystemPrompt().isBlank()
                ? "You are a helpful assistant."
                : render(request.getSystemPrompt(), request.getVariables());
        String model = request.getModel() == null || request.getModel().isBlank() ? "gpt-4o-mini" : request.getModel();
        double temperature = request.getTemperature() == null ? 0.2 : request.getTemperature();

        long start = System.currentTimeMillis();
        try {
            String output = llmClient.chat(system, user, model, temperature);
            Map<String, Object> result = status("OK", null);
            result.put("output", output);
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("model", model);
            result.put("renderedPrompt", user);
            return ApiResult.ok(result);
        } catch (LlmUnavailableException e) {
            Map<String, Object> result = status("LLM_UNAVAILABLE", e.getMessage());
            result.put("renderedPrompt", user);
            result.put("model", model);          // 回显请求的模型：失败侧也要知道打的是哪个配置
            result.put("temperature", temperature);
            return ApiResult.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = status("ERROR", String.valueOf(e.getMessage()));
            result.put("renderedPrompt", user);
            result.put("model", model);
            result.put("latencyMs", System.currentTimeMillis() - start);
            return ApiResult.ok(result);
        }
    }

    /** {{变量}} 渲染：未提供的变量保留原样并标记 */
    private String render(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        Map<String, String> vars = variables == null ? Map.of() : variables;
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = vars.get(key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? matcher.group(0) : value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Map<String, Object> status(String status, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status);
        if (message != null) {
            m.put("message", message);
        }
        return m;
    }
}
