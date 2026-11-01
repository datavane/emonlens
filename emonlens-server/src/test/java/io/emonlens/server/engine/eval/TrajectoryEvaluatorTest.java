package io.emonlens.server.engine.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.storage.entity.EvaluatorEntity;

class TrajectoryEvaluatorTest {

    private final TrajectoryEvaluator evaluator =
            new TrajectoryEvaluator(new ObjectMapper());

    @Test
    void subsetPassesWhenAllRequiredToolsCalled() {
        EvaluatorSpec spec = new EvaluatorSpec();
        spec.setExpectedTools(List.of(tool("query_order"), tool("refund_policy")));
        TrajectoryEvaluator.EvaluationResult result =
                evaluator.evaluate(new EvaluatorEntity(), spec, sample("query_order", "refund_policy", "answer"));
        assertThat(result.label).isEqualTo("PASS");
        assertThat(result.reasonCode).isEqualTo("TRAJECTORY_PASS");
    }

    @Test
    void subsetFailsWhenRequiredToolMissing() {
        EvaluatorSpec spec = new EvaluatorSpec();
        spec.setExpectedTools(List.of(tool("query_order"), tool("refund_policy")));
        TrajectoryEvaluator.EvaluationResult result =
                evaluator.evaluate(new EvaluatorEntity(), spec, sample("query_order"));
        assertThat(result.label).isEqualTo("FAIL");
        assertThat(result.reason).contains("refund_policy");
    }

    @Test
    void orderFailsWhenSequenceWrong() {
        EvaluatorSpec spec = new EvaluatorSpec();
        spec.setTrajectoryMode("ORDER");
        spec.setExpectedTools(List.of(tool("query_order"), tool("refund_policy")));
        TrajectoryEvaluator.EvaluationResult result =
                evaluator.evaluate(new EvaluatorEntity(), spec, sample("refund_policy", "query_order"));
        assertThat(result.label).isEqualTo("FAIL");
        assertThat(result.reason).contains("顺序");
    }

    @Test
    void exactFailsOnExtraTool() {
        EvaluatorSpec spec = new EvaluatorSpec();
        spec.setTrajectoryMode("EXACT");
        spec.setExpectedTools(List.of(tool("query_order")));
        TrajectoryEvaluator.EvaluationResult result =
                evaluator.evaluate(new EvaluatorEntity(), spec, sample("query_order", "extra"));
        assertThat(result.label).isEqualTo("FAIL");
    }

    @Test
    void forbiddenToolFails() {
        EvaluatorSpec spec = new EvaluatorSpec();
        spec.setForbiddenTools(List.of("delete_order"));
        TrajectoryEvaluator.EvaluationResult result =
                evaluator.evaluate(new EvaluatorEntity(), spec, sample("query_order", "delete_order"));
        assertThat(result.label).isEqualTo("FAIL");
        assertThat(result.reason).contains("delete_order");
    }

    @Test
    void maxStepsFails() {
        EvaluatorSpec spec = new EvaluatorSpec();
        spec.setMaxSteps(1);
        TrajectoryEvaluator.EvaluationResult result =
                evaluator.evaluate(new EvaluatorEntity(), spec, sample("a", "b"));
        assertThat(result.label).isEqualTo("FAIL");
        assertThat(result.reason).contains("步数");
    }

    @Test
    void argumentRuleChecksRegex() {
        EvaluatorSpec spec = new EvaluatorSpec();
        EvaluatorSpec.ArgumentRule rule = new EvaluatorSpec.ArgumentRule();
        rule.setTool("query_order");
        rule.setPath("orderId");
        rule.setRequired(true);
        rule.setRegex("^[0-9]{6}$");
        spec.setArgumentRules(List.of(rule));

        EvaluationSample good = sample("query_order");
        good.getToolsCalled().get(0).put("inputParameters", Map.of("orderId", "123456"));
        assertThat(evaluator.evaluate(new EvaluatorEntity(), spec, good).label).isEqualTo("PASS");

        EvaluationSample bad = sample("query_order");
        bad.getToolsCalled().get(0).put("inputParameters", Map.of("orderId", "abc"));
        assertThat(evaluator.evaluate(new EvaluatorEntity(), spec, bad).label).isEqualTo("FAIL");
    }

    @Test
    void missingToolsIsUnknown() {
        EvaluatorSpec spec = new EvaluatorSpec();
        spec.setExpectedTools(List.of(tool("query_order")));
        TrajectoryEvaluator.EvaluationResult result =
                evaluator.evaluate(new EvaluatorEntity(), spec, new EvaluationSample());
        assertThat(result.label).isEqualTo("UNKNOWN");
        assertThat(result.reasonCode).isEqualTo("TOOLS_CALLED_MISSING");
    }

    @Test
    void repeatedBlockFailsByDefault() {
        EvaluatorSpec spec = new EvaluatorSpec();
        TrajectoryEvaluator.EvaluationResult result =
                evaluator.evaluate(new EvaluatorEntity(), spec,
                        sample("a", "b", "a", "b"));
        assertThat(result.label).isEqualTo("FAIL");
        assertThat(result.reason).contains("重复");
    }

    private EvaluatorSpec.ExpectedTool tool(String name) {
        EvaluatorSpec.ExpectedTool tool = new EvaluatorSpec.ExpectedTool();
        tool.setName(name);
        tool.setRequired(true);
        return tool;
    }

    private EvaluationSample sample(String... names) {
        EvaluationSample sample = new EvaluationSample();
        List<Map<String, Object>> tools = new ArrayList<>();
        int order = 0;
        for (String name : names) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", name);
            tool.put("order", order++);
            tools.add(tool);
        }
        sample.setToolsCalled(tools);
        return sample;
    }
}
