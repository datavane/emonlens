package io.emonlens.server.engine.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JudgePresetRegistryTest {

    private final JudgePresetRegistry registry = new JudgePresetRegistry();

    @Test
    void ragFaithfulnessGetsClaimDefaults() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("preset", "rag-faithfulness");
        registry.applyDefaults("LLM_JUDGE", spec);

        assertThat(spec.get("judgeMode")).isEqualTo("CLAIMS");
        assertThat(spec.get("judgeTemplateKey")).isEqualTo("platform/claim-v1");
        @SuppressWarnings("unchecked")
        List<String> requiredFields = (List<String>) spec.get("requiredFields");
        assertThat(requiredFields).containsExactly("candidate_output", "retrieval_context");
        Map<?, ?> policy = (Map<?, ?>) spec.get("claimsPolicy");
        assertThat(policy.get("minPassRatio")).isEqualTo(0.8);
        assertThat(policy.get("unknownPolicy")).isEqualTo("BLOCK");
    }

    @Test
    void explicitValuesAreNotOverwritten() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("preset", "task-completion");
        spec.put("judgeMode", "HOLISTIC");
        spec.put("judgeTemplateKey", "platform/holistic-v1");
        registry.applyDefaults("LLM_JUDGE", spec);
        assertThat(spec.get("judgeMode")).isEqualTo("HOLISTIC");
        assertThat(spec.get("judgeTemplateKey")).isEqualTo("platform/holistic-v1");
    }

    @Test
    void nonLlmEvaluatorIsUntouched() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("preset", "rag-faithfulness");
        registry.applyDefaults("EXPRESSION", spec);
        assertThat(spec).doesNotContainKey("judgeMode");
    }

    @Test
    void registryContainsAgentRagAndConversationPresets() {
        assertThat(registry.list()).hasSizeGreaterThanOrEqualTo(13);
        assertThat(registry.get("task-completion")).isNotNull();
        assertThat(registry.get("rag-faithfulness")).isNotNull();
        assertThat(registry.get("conversation-goal-accuracy")).isNotNull();
        assertThat(registry.get("does-not-exist")).isNull();
    }
}
