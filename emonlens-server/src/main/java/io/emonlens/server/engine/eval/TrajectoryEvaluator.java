package io.emonlens.server.engine.eval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.storage.entity.EvaluatorEntity;

import lombok.RequiredArgsConstructor;

/**
 * TRAJECTORY 确定性评测：只看工具调用序列，不调用 LLM。
 *
 * <p>支持期望工具集合/顺序/完全匹配、禁用工具、最大步数、重复块检测和参数规则。
 * 证据不足（没有 toolsCalled / 参数无法解析）返回 UNKNOWN，不判 FAIL。</p>
 */
@Component
@RequiredArgsConstructor
public class TrajectoryEvaluator {

    private static final int MAX_CYCLE_BLOCK = 8;

    private final ObjectMapper objectMapper;

    public EvaluationResult evaluate(EvaluatorEntity evaluator, EvaluatorSpec spec, EvaluationSample sample) {
        List<Map<String, Object>> actual = sample.getToolsCalled() == null
                ? new ArrayList<>() : sample.getToolsCalled();
        if (actual.isEmpty()) {
            return EvaluationResult.unknown(EvaluatorEntity.LABEL_UNKNOWN, "TOOLS_CALLED_MISSING",
                    "样本没有工具调用证据（TRACE 模式需要 tool span；REPLAY 不产生工具轨迹）");
        }
        List<EvaluatorSpec.ExpectedTool> expected = expectedTools(spec, sample);
        List<String> actualNames = names(actual);
        List<Map<String, Object>> matches = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        // 禁用工具
        if (spec.getForbiddenTools() != null) {
            for (String forbidden : spec.getForbiddenTools()) {
                if (forbidden != null && actualNames.contains(forbidden)) {
                    failures.add("出现禁用工具 " + forbidden);
                }
            }
        }
        // 最大步数
        if (spec.getMaxSteps() != null && spec.getMaxSteps() > 0
                && actual.size() > spec.getMaxSteps()) {
            failures.add("工具步数 " + actual.size() + " 超过上限 " + spec.getMaxSteps());
        }
        // 重复块
        if (!Boolean.TRUE.equals(spec.getAllowCycles())) {
            List<String> cycle = findCycle(actualNames);
            if (!cycle.isEmpty()) {
                failures.add("检测到重复工具块 " + cycle);
            }
        }
        // 期望工具
        String mode = spec.getTrajectoryMode() == null || spec.getTrajectoryMode().isBlank()
                ? "SUBSET" : spec.getTrajectoryMode().trim().toUpperCase();
        List<String> requiredNames = new ArrayList<>();
        for (EvaluatorSpec.ExpectedTool tool : expected) {
            if (tool == null || tool.getName() == null || tool.getName().isBlank()) {
                continue;
            }
            boolean required = !Boolean.FALSE.equals(tool.getRequired());
            if (required) {
                requiredNames.add(tool.getName());
            }
            boolean present = actualNames.contains(tool.getName());
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("id", tool.getName());
            match.put("label", "期望工具 " + tool.getName());
            match.put("required", required);
            match.put("passed", present);
            match.put("reason", present ? "已调用" : (required ? "未调用" : "可选工具未调用"));
            matches.add(match);
            if (required && !present) {
                failures.add("缺少必需工具 " + tool.getName());
            }
        }
        if (!requiredNames.isEmpty()) {
            if ("ORDER".equals(mode) && !isSubsequence(requiredNames, actualNames)) {
                failures.add("工具顺序不符合期望：" + requiredNames + "，实际 " + actualNames);
            } else if ("EXACT".equals(mode) && !requiredNames.equals(actualNames)) {
                failures.add("工具序列不完全匹配：期望 " + requiredNames + "，实际 " + actualNames);
            }
        }
        // 参数规则
        if (spec.getArgumentRules() != null) {
            for (EvaluatorSpec.ArgumentRule rule : spec.getArgumentRules()) {
                String failure = checkArgumentRule(rule, actual);
                if (failure != null) {
                    failures.add(failure);
                }
                Map<String, Object> match = new LinkedHashMap<>();
                match.put("id", "arg:" + (rule == null ? "?" : rule.getTool() + "." + rule.getPath()));
                match.put("label", "参数规则 " + (rule == null ? "?" : rule.getTool() + "." + rule.getPath()));
                match.put("passed", failure == null);
                match.put("reason", failure == null ? "通过" : failure);
                matches.add(match);
            }
        }

        if (!failures.isEmpty()) {
            return EvaluationResult.fail("TRAJECTORY_FAIL", String.join("；", failures), matches);
        }
        return EvaluationResult.pass("TRAJECTORY_PASS",
                "轨迹检查通过（工具 " + actual.size() + " 步）", matches);
    }

    private List<EvaluatorSpec.ExpectedTool> expectedTools(EvaluatorSpec spec, EvaluationSample sample) {
        if (spec.getExpectedTools() != null && !spec.getExpectedTools().isEmpty()) {
            return spec.getExpectedTools();
        }
        List<EvaluatorSpec.ExpectedTool> result = new ArrayList<>();
        for (Map<String, Object> raw : sample.getExpectedTools()) {
            if (raw == null || raw.get("name") == null) {
                continue;
            }
            EvaluatorSpec.ExpectedTool tool = new EvaluatorSpec.ExpectedTool();
            tool.setName(String.valueOf(raw.get("name")));
            tool.setRequired(raw.get("required") instanceof Boolean ? (Boolean) raw.get("required") : true);
            result.add(tool);
        }
        return result;
    }

    private List<String> names(List<Map<String, Object>> tools) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            if (tool != null && tool.get("name") != null) {
                names.add(String.valueOf(tool.get("name")));
            }
        }
        return names;
    }

    private boolean isSubsequence(List<String> expected, List<String> actual) {
        int index = 0;
        for (String name : actual) {
            if (index < expected.size() && expected.get(index).equals(name)) {
                index++;
            }
        }
        return index == expected.size();
    }

    /** 连续重复块：长度 2..8，重复 >=2 次。 */
    private List<String> findCycle(List<String> actions) {
        int n = actions.size();
        for (int len = 2; len <= Math.min(MAX_CYCLE_BLOCK, n / 2); len++) {
            for (int start = 0; start + len * 2 <= n; start++) {
                int repeats = 1;
                while (start + (repeats + 1) * len <= n
                        && actions.subList(start, start + len)
                        .equals(actions.subList(start + repeats * len, start + (repeats + 1) * len))) {
                    repeats++;
                }
                if (repeats >= 2) {
                    return new ArrayList<>(actions.subList(start, start + len));
                }
            }
        }
        return List.of();
    }

    private String checkArgumentRule(EvaluatorSpec.ArgumentRule rule, List<Map<String, Object>> actual) {
        if (rule == null || rule.getTool() == null || rule.getTool().isBlank()
                || rule.getPath() == null || rule.getPath().isBlank()) {
            return null;
        }
        Map<String, Object> tool = null;
        for (Map<String, Object> candidate : actual) {
            if (candidate != null && rule.getTool().equals(String.valueOf(candidate.get("name")))) {
                tool = candidate;
                break;
            }
        }
        if (tool == null) {
            return "参数规则引用的工具 " + rule.getTool() + " 未调用";
        }
        Object params = tool.get("inputParameters");
        Object value = pathValue(params, rule.getPath());
        boolean required = !Boolean.FALSE.equals(rule.getRequired());
        if (value == null) {
            return required ? "工具 " + rule.getTool() + " 缺少参数 " + rule.getPath() : null;
        }
        String text = String.valueOf(value);
        if (rule.getRegex() != null && !rule.getRegex().isBlank()) {
            try {
                if (!Pattern.compile(rule.getRegex()).matcher(text).find()) {
                    return "工具 " + rule.getTool() + " 参数 " + rule.getPath()
                            + " 不匹配正则 " + rule.getRegex();
                }
            } catch (Exception e) {
                return "参数规则正则非法：" + e.getMessage();
            }
        }
        if (rule.getEquals() != null && !rule.getEquals().equals(text)) {
            return "工具 " + rule.getTool() + " 参数 " + rule.getPath()
                    + " 期望 " + rule.getEquals() + "，实际 " + text;
        }
        return null;
    }

    private Object pathValue(Object value, String path) {
        Object current = value;
        if (current == null) {
            return null;
        }
        if (current instanceof String) {
            try {
                current = objectMapper.readValue(String.valueOf(current), Object.class);
            } catch (Exception e) {
                return null;
            }
        }
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<?, ?>) current).get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    public static final class EvaluationResult {
        final String label;
        final String reasonCode;
        final String reason;
        final List<Map<String, Object>> matches;

        private EvaluationResult(String label, String reasonCode, String reason,
                                 List<Map<String, Object>> matches) {
            this.label = label;
            this.reasonCode = reasonCode;
            this.reason = reason;
            this.matches = matches;
        }

        static EvaluationResult pass(String code, String reason, List<Map<String, Object>> matches) {
            return new EvaluationResult(EvaluatorEntity.LABEL_PASS, code, reason, matches);
        }

        static EvaluationResult fail(String code, String reason, List<Map<String, Object>> matches) {
            return new EvaluationResult(EvaluatorEntity.LABEL_FAIL, code, reason, matches);
        }

        static EvaluationResult unknown(String label, String code, String reason) {
            return new EvaluationResult(EvaluatorEntity.LABEL_UNKNOWN, code, reason, List.of());
        }
    }
}
