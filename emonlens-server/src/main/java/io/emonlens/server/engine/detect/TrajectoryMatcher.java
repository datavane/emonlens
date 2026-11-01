package io.emonlens.server.engine.detect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.emonlens.server.engine.monitor.MonitorSpec;
import io.emonlens.server.engine.monitor.MonitorSpec.RuleSpec;
import io.emonlens.server.storage.entity.SpanEventEntity;

/**
 * Trajectory Monitor：动作序列上的确定性算子（单次扫描，禁止逐条查库）。
 * V1 支持：requires_before / occurs_without / max_steps / no_cycle。
 */
public final class TrajectoryMatcher {

    private static final int MAX_CYCLE_LENGTH = 8;

    private TrajectoryMatcher() {
    }

    public static List<Violation> evaluate(List<SpanEventEntity> spans, MonitorSpec spec) {
        List<Violation> violations = new ArrayList<>();
        if (spec.getRules() == null || spec.getRules().isEmpty()) {
            return violations;
        }
        List<String> actions = TrajectorySteps.actionSequence(spans);
        List<String> spanIds = stepSpanIds(spans);
        for (RuleSpec rule : spec.getRules()) {
            Violation violation = evaluateRule(rule, actions, spanIds);
            if (violation != null) {
                violations.add(violation);
            }
        }
        return violations;
    }

    private static Violation evaluateRule(RuleSpec rule, List<String> actions, List<String> spanIds) {
        switch (rule.getType() == null ? "" : rule.getType()) {
            case RuleSpec.TYPE_REQUIRES_BEFORE:
                return checkRequiresBefore(rule, actions, spanIds);
            case RuleSpec.TYPE_OCCURS_WITHOUT:
                return checkOccursWithout(rule, actions, spanIds);
            case RuleSpec.TYPE_MAX_STEPS:
                return checkMaxSteps(rule, actions);
            case RuleSpec.TYPE_NO_CYCLE:
                return checkNoCycle(rule, actions, spanIds);
            default:
                return null;
        }
    }

    /** action 发生过，但其 required 前置动作未全部在首次 action 之前发生 */
    private static Violation checkRequiresBefore(RuleSpec rule, List<String> actions, List<String> spanIds) {
        if (rule.getAction() == null || rule.getRequired() == null || rule.getRequired().isEmpty()) {
            return null;
        }
        int firstActionIdx = actions.indexOf(rule.getAction());
        if (firstActionIdx < 0) {
            return null;
        }
        Set<String> seen = new HashSet<>(actions.subList(0, firstActionIdx));
        List<String> missing = new ArrayList<>();
        for (String required : rule.getRequired()) {
            if (!seen.contains(required)) {
                missing.add(required);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("rule", "requires_before");
        evidence.put("action", rule.getAction());
        evidence.put("missing", missing);
        return new Violation("action '" + rule.getAction() + "' executed without required steps " + missing,
                spanIdAt(spanIds, actions, firstActionIdx), evidence);
    }

    /** action 与 forbidden 动作共现即违规（occurs without B = B 不允许出现） */
    private static Violation checkOccursWithout(RuleSpec rule, List<String> actions, List<String> spanIds) {
        if (rule.getAction() == null || rule.getForbidden() == null || rule.getForbidden().isEmpty()) {
            return null;
        }
        if (!actions.contains(rule.getAction())) {
            return null;
        }
        for (String forbidden : rule.getForbidden()) {
            if (actions.contains(forbidden)) {
                Map<String, Object> evidence = new HashMap<>();
                evidence.put("rule", "occurs_without");
                evidence.put("action", rule.getAction());
                evidence.put("forbidden", forbidden);
                return new Violation("action '" + rule.getAction() + "' co-occurred with forbidden '"
                        + forbidden + "'", null, evidence);
            }
        }
        return null;
    }

    private static Violation checkMaxSteps(RuleSpec rule, List<String> actions) {
        if (rule.getLimit() == null || rule.getLimit() <= 0) {
            return null;
        }
        if (actions.size() <= rule.getLimit()) {
            return null;
        }
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("rule", "max_steps");
        evidence.put("limit", rule.getLimit());
        evidence.put("actual", actions.size());
        return new Violation("trajectory steps " + actions.size() + " exceeded limit " + rule.getLimit(),
                null, evidence);
    }

    /** 检测连续重复的循环块（长度 2..8，重复次数 >= minCycles，默认 2） */
    static CycleInfo findCycle(List<String> actions, int minCycles) {
        int n = actions.size();
        int maxLen = Math.min(MAX_CYCLE_LENGTH, n / Math.max(1, minCycles));
        for (int len = 2; len <= maxLen; len++) {
            for (int start = 0; start + len * minCycles <= n; start++) {
                int repeats = countConsecutiveRepeats(actions, start, len);
                if (repeats >= minCycles) {
                    return new CycleInfo(actions.subList(start, start + len), repeats);
                }
            }
        }
        return null;
    }

    private static int countConsecutiveRepeats(List<String> actions, int start, int len) {
        int repeats = 1;
        while (start + (repeats + 1) * len <= actions.size()
                && equalBlock(actions, start, start + repeats * len, len)) {
            repeats++;
        }
        return repeats;
    }

    private static boolean equalBlock(List<String> actions, int aStart, int bStart, int len) {
        for (int i = 0; i < len; i++) {
            if (!actions.get(aStart + i).equals(actions.get(bStart + i))) {
                return false;
            }
        }
        return true;
    }

    private static Violation checkNoCycle(RuleSpec rule, List<String> actions, List<String> spanIds) {
        int minCycles = rule.getMinCycles() == null ? 2 : rule.getMinCycles();
        CycleInfo cycle = findCycle(actions, minCycles);
        if (cycle == null) {
            return null;
        }
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("rule", "no_cycle");
        evidence.put("cycle", cycle.getActions());
        evidence.put("repeats", cycle.getRepeats());
        return new Violation("cycle detected: " + cycle.getActions() + " repeated " + cycle.getRepeats()
                + " times", null, evidence);
    }

    private static List<String> stepSpanIds(List<SpanEventEntity> spans) {
        List<String> spanIds = new ArrayList<>();
        for (SpanEventEntity span : spans) {
            if (TrajectorySteps.isStep(span)) {
                spanIds.add(span.getSpanId());
            }
        }
        return spanIds;
    }

    private static String spanIdAt(List<String> spanIds, List<String> actions, int index) {
        return index < spanIds.size() ? spanIds.get(index) : null;
    }

    /** 循环检测结果 */
    static final class CycleInfo {
        private final List<String> actions;
        private final int repeats;

        CycleInfo(List<String> actions, int repeats) {
            this.actions = actions;
            this.repeats = repeats;
        }

        List<String> getActions() {
            return actions;
        }

        int getRepeats() {
            return repeats;
        }
    }
}
