package io.emonlens.server.engine.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 指标预设注册表：预设 = 模板 + 判分模式 + 所需证据 + 一段任务说明。
 *
 * <p>核心不新增 Java 指标类；新增指标只需在这里注册，并在
 * {@code EvaluatorService} 校验、{@code EvaluationRunService} 通用 runner 中生效。</p>
 */
@Component
public class JudgePresetRegistry {

    public static final String CLAIMS = "CLAIMS";
    public static final String HOLISTIC = "HOLISTIC";

    private final Map<String, Preset> presets = new LinkedHashMap<>();

    public JudgePresetRegistry() {
        // Agentic
        register("task-completion", CLAIMS, JudgeTemplateRegistry.CLAIM_KEY,
                List.of("input", "candidate_output"),
                "判断候选输出是否真正完成了用户任务；区分部分完成与完全完成。");
        register("plan-adherence", CLAIMS, JudgeTemplateRegistry.CLAIM_KEY,
                List.of("input", "trace_digest"),
                "从轨迹中识别计划，再判断实际执行是否遵循计划。");
        register("argument-correctness", CLAIMS, JudgeTemplateRegistry.CLAIM_KEY,
                List.of("input", "tools_called"),
                "逐条判断工具调用的参数是否与任务/上下文一致。");
        register("step-efficiency", HOLISTIC, JudgeTemplateRegistry.HOLISTIC_KEY,
                List.of("input", "trace_digest"),
                "判断工具/步骤是否冗余、低效或绕路；0-10 分，10 表示最有效。");
        // RAG
        register("rag-faithfulness", CLAIMS, JudgeTemplateRegistry.CLAIM_KEY,
                List.of("candidate_output", "retrieval_context"),
                "逐条判断候选答案中的 claim 是否被检索上下文支撑。");
        register("rag-answer-relevancy", CLAIMS, JudgeTemplateRegistry.CLAIM_KEY,
                List.of("input", "candidate_output"),
                "判断候选答案是否回应用户问题，逐条评估 statement 的相关性。");
        register("rag-contextual-recall", CLAIMS, JudgeTemplateRegistry.CLAIM_KEY,
                List.of("expected_output", "retrieval_context"),
                "判断参考答案中的每条 statement 是否能在检索上下文中找到依据。");
        register("rag-contextual-precision", CLAIMS, JudgeTemplateRegistry.CLAIM_KEY,
                List.of("input", "expected_output", "retrieval_context"),
                "判断检索上下文是否相关、排序是否合理；越相关的上下文应越靠前。");
        register("rag-contextual-relevancy", CLAIMS, JudgeTemplateRegistry.CLAIM_KEY,
                List.of("input", "retrieval_context"),
                "逐条判断检索上下文中的 statement 是否与用户问题相关。");
        register("rag-hallucination", CLAIMS, JudgeTemplateRegistry.CLAIM_KEY,
                List.of("candidate_output", "retrieval_context"),
                "逐条判断候选答案中的 claim 是否被检索上下文否定（幻觉）。");
        // Multi-turn
        register("conversation-goal-accuracy", CLAIMS, JudgeTemplateRegistry.CLAIM_KEY,
                List.of("turns"),
                "从多轮会话中推断目标步骤，并判断每步是否达成。");
        register("conversation-knowledge-retention", CLAIMS, JudgeTemplateRegistry.CLAIM_KEY,
                List.of("turns"),
                "判断后续轮次是否保持前面已确认的事实，或出现遗忘/自相矛盾。");
        register("conversation-turn-faithfulness", CLAIMS, JudgeTemplateRegistry.CLAIM_KEY,
                List.of("turns"),
                "逐轮判断 assistant 的陈述是否被当轮检索上下文支撑。");
    }

    private void register(String key, String mode, String templateKey,
                          List<String> requiredFields, String instruction) {
        presets.put(key, new Preset(key, mode, templateKey,
                new ArrayList<>(requiredFields), instruction));
    }

    public Preset get(String key) {
        return key == null ? null : presets.get(key.trim());
    }

    public List<Preset> list() {
        return new ArrayList<>(presets.values());
    }

    /** 把预设默认值写回 spec，保证 evaluator version / run snapshot 显式冻结。 */
    public Map<String, Object> applyDefaults(String type, Map<String, Object> spec) {
        if (spec == null || !"LLM_JUDGE".equals(type)) {
            return spec;
        }
        Object raw = spec.get("preset");
        Preset preset = raw == null ? null : get(String.valueOf(raw));
        if (preset == null) {
            return spec;
        }
        putIfBlank(spec, "judgeMode", preset.getJudgeMode());
        putIfBlank(spec, "judgeTemplateKey", preset.getTemplateKey());
        if (spec.get("requiredFields") == null) {
            spec.put("requiredFields", new ArrayList<>(preset.getRequiredFields()));
        }
        if (CLAIMS.equals(preset.getJudgeMode()) && spec.get("claimsPolicy") == null) {
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("maxClaims", 20);
            policy.put("requireEvidence", true);
            policy.put("unknownPolicy", "BLOCK");
            policy.put("minPassRatio", 0.8);
            spec.put("claimsPolicy", policy);
        }
        return spec;
    }

    private void putIfBlank(Map<String, Object> spec, String key, String value) {
        Object current = spec.get(key);
        if (current == null || String.valueOf(current).isBlank()) {
            spec.put(key, value);
        }
    }

    public static final class Preset {
        private final String key;
        private final String judgeMode;
        private final String templateKey;
        private final List<String> requiredFields;
        private final String instruction;

        Preset(String key, String judgeMode, String templateKey,
               List<String> requiredFields, String instruction) {
            this.key = key;
            this.judgeMode = judgeMode;
            this.templateKey = templateKey;
            this.requiredFields = requiredFields;
            this.instruction = instruction;
        }

        public String getKey() {
            return key;
        }

        public String getJudgeMode() {
            return judgeMode;
        }

        public String getTemplateKey() {
            return templateKey;
        }

        public List<String> getRequiredFields() {
            return requiredFields;
        }

        public String getInstruction() {
            return instruction;
        }
    }
}
