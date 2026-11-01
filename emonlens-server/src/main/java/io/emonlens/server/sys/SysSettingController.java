package io.emonlens.server.sys;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.config.SysSettingService;
import io.emonlens.server.engine.eval.HttpLlmClient;
import io.emonlens.server.engine.eval.LlmUnavailableException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 系统运行时设置（LLM 端点等），作用域为全局（跨工作空间）。 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sys/settings")
@RequiredArgsConstructor
public class SysSettingController {

    private final SysSettingService sysSettingService;
    private final EmonLensProperties properties;
    private final HttpLlmClient llmClient;

    @RequiresPermission(Permissions.SYS_SETTING_MANAGE)
    @GetMapping("/eval-llm")
    public ApiResult<Map<String, Object>> getEvalLlm() {
        SysSettingService.EvalLlm db = sysSettingService.getEvalLlm();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocol", db != null && db.configured() && db.getProtocol() != null && !db.getProtocol().isEmpty()
                ? HttpLlmClient.normalizeProtocol(db.getProtocol())
                : HttpLlmClient.normalizeProtocol(properties.getEval().getLlm().getProtocol()));
        out.put("baseUrl", db != null && db.getBaseUrl() != null ? db.getBaseUrl() : properties.getEval().getLlm().getBaseUrl());
        out.put("model", db != null && db.getModel() != null ? db.getModel() : properties.getEval().getLlm().getModel());
        out.put("timeoutMs", db != null && db.getTimeoutMs() != null ? db.getTimeoutMs() : properties.getEval().getLlm().getTimeoutMs());
        out.put("source", db != null && db.configured() ? "DB" : "YML");
        out.put("apiKeyMasked", mask(effectiveApiKey(db)));
        return ApiResult.ok(out);
    }

    @Audit(action = "sys.setting.eval_llm.update", resourceType = "setting")
    @RequiresPermission(Permissions.SYS_SETTING_MANAGE)
    @PutMapping("/eval-llm")
    public ApiResult<Boolean> saveEvalLlm(@RequestBody EvalLlmRequest request) {
        if (request.getBaseUrl() != null && !request.getBaseUrl().isEmpty()
                && !request.getBaseUrl().startsWith("http://") && !request.getBaseUrl().startsWith("https://")) {
            return ApiResult.error(io.emonlens.common.ErrorCode.BAD_REQUEST, "base-url 必须以 http(s):// 开头");
        }
        if (!validProtocol(request.getProtocol())) {
            return ApiResult.error(io.emonlens.common.ErrorCode.BAD_REQUEST, "protocol 只支持 openai 或 anthropic");
        }
        SysSettingService.EvalLlm settings = new SysSettingService.EvalLlm();
        // 清空 base-url 视为回退 yml：协议也一并置空，避免残留协议覆盖 yml 配置。
        settings.setProtocol(trimToNull(request.getBaseUrl()) == null
                ? null : HttpLlmClient.normalizeProtocol(request.getProtocol()));
        settings.setBaseUrl(trimToNull(request.getBaseUrl()));
        // 留空 = 沿用已保存 Key（设置页明文只回显尾 4 位，用户无法重复输入原值；旧实现会把 Key 抹掉）。
        String requestedKey = trimToNull(request.getApiKey());
        if (requestedKey != null) {
            settings.setApiKey(requestedKey);
        } else {
            SysSettingService.EvalLlm existing = sysSettingService.getEvalLlm();
            settings.setApiKey(existing == null ? null : existing.getApiKey());
        }
        settings.setModel(trimToNull(request.getModel()));
        settings.setTimeoutMs(request.getTimeoutMs());
        sysSettingService.saveEvalLlm(settings);
        return ApiResult.ok(true);
    }

    /** 连通性测试：body 为空测当前生效配置；带 baseUrl 时测表单里未保存的配置。 */
    @RequiresPermission(Permissions.SYS_SETTING_MANAGE)
    @PostMapping("/eval-llm/test")
    public ApiResult<Map<String, Object>> testEvalLlm(@RequestBody(required = false) EvalLlmRequest request) {
        if (request != null && !validProtocol(request.getProtocol())) {
            return ApiResult.error(io.emonlens.common.ErrorCode.BAD_REQUEST, "protocol 只支持 openai 或 anthropic");
        }
        SysSettingService.EvalLlm saved = sysSettingService.getEvalLlm();
        SysSettingService.EvalLlm override = null;
        if (request != null && request.getBaseUrl() != null && !request.getBaseUrl().isEmpty()) {
            override = new SysSettingService.EvalLlm();
            // 表单只改 URL/协议时，Key 与模型沿用已保存值（设置页 Key 留空=不修改，测试同理）。
            override.setProtocol(HttpLlmClient.normalizeProtocol(
                    trimToNull(request.getProtocol()) != null ? request.getProtocol()
                            : (saved != null && saved.getProtocol() != null
                                    ? saved.getProtocol() : properties.getEval().getLlm().getProtocol())));
            override.setBaseUrl(request.getBaseUrl().trim());
            override.setApiKey(trimToNull(request.getApiKey()) != null
                    ? trimToNull(request.getApiKey()) : effectiveApiKey(saved));
            override.setModel(trimToNull(request.getModel()));
            override.setTimeoutMs(request.getTimeoutMs());
        }
        String model = override != null && override.getModel() != null ? override.getModel()
                : (saved != null && saved.getModel() != null
                        ? saved.getModel() : properties.getEval().getLlm().getModel());
        long start = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        String protocol = override != null ? HttpLlmClient.normalizeProtocol(override.getProtocol())
                : HttpLlmClient.normalizeProtocol(llmClient.effectiveConfig().getProtocol());
        try {
            String answer = llmClient.chat(override,
                    "You are a connectivity probe.", "Reply with exactly: pong", model, 0.0).content();
            out.put("ok", true);
            out.put("model", model);
            out.put("protocol", protocol);
            out.put("latencyMs", System.currentTimeMillis() - start);
            String reply = answer == null ? "" : answer.trim();
            out.put("reply", reply.substring(0, Math.min(reply.length(), 120)));
        } catch (LlmUnavailableException e) {
            out.put("ok", false);
            out.put("protocol", protocol);
            out.put("error", e.getMessage());
        } catch (Exception e) {
            log.warn("llm connectivity test failed", e);
            out.put("ok", false);
            out.put("protocol", protocol);
            out.put("error", e.getMessage());
        }
        return ApiResult.ok(out);
    }

    private boolean validProtocol(String protocol) {
        return protocol == null || protocol.trim().isEmpty()
                || HttpLlmClient.PROTOCOL_OPENAI.equalsIgnoreCase(protocol.trim())
                || HttpLlmClient.PROTOCOL_ANTHROPIC.equalsIgnoreCase(protocol.trim());
    }

    private String effectiveApiKey(SysSettingService.EvalLlm db) {
        if (db != null && db.getApiKey() != null && !db.getApiKey().isEmpty()) {
            return db.getApiKey();
        }
        return properties.getEval().getLlm().getApiKey();
    }

    private String mask(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        return key.length() <= 4 ? "••••" : "••••" + key.substring(key.length() - 4);
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @RequiresPermission(Permissions.SYS_SETTING_MANAGE)
    @GetMapping("/eval-sampling")
    public ApiResult<Map<String, Object>> getEvalSampling() {
        SysSettingService.EvalSampling db = sysSettingService.getEvalSampling();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("defaultRate", db != null && db.getDefaultRate() != null ? db.getDefaultRate() : properties.getEval().getDefaultRate());
        out.put("maxPerHour", db != null && db.getMaxPerHour() != null ? db.getMaxPerHour() : properties.getEval().getMaxPerHour());
        out.put("autoRunEnabled", db != null && db.getAutoRunEnabled() != null ? db.getAutoRunEnabled() : properties.getEval().isAutoRunEnabled());
        out.put("source", db != null ? "DB" : "YML");
        return ApiResult.ok(out);
    }

    @Audit(action = "sys.setting.eval_sampling.update", resourceType = "setting")
    @RequiresPermission(Permissions.SYS_SETTING_MANAGE)
    @PutMapping("/eval-sampling")
    public ApiResult<Boolean> saveEvalSampling(@RequestBody SysSettingService.EvalSampling request) {
        if (request.getDefaultRate() != null && (request.getDefaultRate() < 0 || request.getDefaultRate() > 1)) {
            return ApiResult.error(io.emonlens.common.ErrorCode.BAD_REQUEST, "defaultRate 必须在 0-1 之间");
        }
        SysSettingService.EvalSampling settings = new SysSettingService.EvalSampling();
        settings.setDefaultRate(request.getDefaultRate());
        settings.setMaxPerHour(request.getMaxPerHour());
        settings.setAutoRunEnabled(request.getAutoRunEnabled());
        sysSettingService.saveEvalSampling(settings);
        return ApiResult.ok(true);
    }

    @RequiresPermission(Permissions.SYS_SETTING_MANAGE)
    @GetMapping("/retention")
    public ApiResult<Map<String, Object>> getRetention() {
        SysSettingService.Retention db = sysSettingService.getRetention();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spanDays", db != null && db.getSpanDays() != null ? db.getSpanDays() : properties.getRetention().getSpanDays());
        out.put("contentDays", db != null && db.getContentDays() != null ? db.getContentDays() : properties.getRetention().getContentDays());
        out.put("eventDays", db != null && db.getEventDays() != null ? db.getEventDays() : properties.getRetention().getEventDays());
        out.put("source", db != null ? "DB" : "YML");
        return ApiResult.ok(out);
    }

    @Audit(action = "sys.setting.retention.update", resourceType = "setting")
    @RequiresPermission(Permissions.SYS_SETTING_MANAGE)
    @PutMapping("/retention")
    public ApiResult<Boolean> saveRetention(@RequestBody SysSettingService.Retention request) {
        for (Integer v : new Integer[]{request.getSpanDays(), request.getContentDays(), request.getEventDays()}) {
            if (v != null && v < 0) {
                return ApiResult.error(io.emonlens.common.ErrorCode.BAD_REQUEST, "保留天数不能为负（0 = 不清理）");
            }
        }
        sysSettingService.saveRetention(request);
        return ApiResult.ok(true);
    }

    @Data
    public static class EvalLlmRequest {
        /** openai | anthropic；空 = openai */
        private String protocol;
        private String baseUrl;
        private String apiKey;
        private String model;
        private Long timeoutMs;
    }
}
