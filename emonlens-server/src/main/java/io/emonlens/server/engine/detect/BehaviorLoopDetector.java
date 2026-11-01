package io.emonlens.server.engine.detect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.engine.monitor.MonitorSpec.RuleSpec;
import io.emonlens.server.storage.entity.SpanEventEntity;

import lombok.RequiredArgsConstructor;

/**
 * 内置 Behavior Loop 检测（V1.1 §14.2 的三种）：
 * 1. EXACT_REPEAT：相同 tool 连续重复 >= maxConsecutiveRepeat；
 * 2. CYCLE：连续重复的循环块重复 >= minCycleRepeat；
 * 3. BUDGET_OVERRUN：步骤数 >= maxSteps。
 * 内容采集 NONE 模式下不做输入归一化，仅按动作名判定。
 */
@Component
@RequiredArgsConstructor
public class BehaviorLoopDetector {

    public static final String KIND_EXACT_REPEAT = "EXACT_REPEAT";
    public static final String KIND_CYCLE = "CYCLE";
    public static final String KIND_BUDGET_OVERRUN = "BUDGET_OVERRUN";

    private final EmonLensProperties properties;

    public List<Violation> detect(List<SpanEventEntity> spans) {
        List<Violation> violations = new ArrayList<>();
        // 带输入指纹：同工具不同参数不算重复（CLI Agent 连续 Bash 属正常，仅工具名会误报）
        List<String> actions = TrajectorySteps.actionKeySequence(spans);
        List<String> spanIds = new ArrayList<>();
        for (SpanEventEntity span : spans) {
            if (TrajectorySteps.isStep(span)) {
                spanIds.add(span.getSpanId());
            }
        }
        detectExactRepeat(actions, spanIds, violations);
        detectCycle(actions, violations);
        detectBudgetOverrun(actions, violations);
        return violations;
    }

    private void detectExactRepeat(List<String> actions, List<String> spanIds, List<Violation> violations) {
        int threshold = properties.getBehavior().getMaxConsecutiveRepeat();
        int runStart = 0;
        while (runStart < actions.size()) {
            int runEnd = runStart + 1;
            while (runEnd < actions.size() && actions.get(runEnd).equals(actions.get(runStart))) {
                runEnd++;
            }
            int runLength = runEnd - runStart;
            if (runLength >= threshold) {
                Map<String, Object> evidence = new HashMap<>();
                evidence.put("loop_kind", KIND_EXACT_REPEAT);
                evidence.put("action", TrajectorySteps.displayName(actions.get(runStart)));
                evidence.put("repeat_count", runLength);
                evidence.put("span_ids", spanIds.subList(runStart, runEnd));
                violations.add(new Violation("action '" + TrajectorySteps.displayName(actions.get(runStart))
                        + "' repeated " + runLength + " times with identical input", null, evidence));
            }
            runStart = runEnd;
        }
    }

    private void detectCycle(List<String> actions, List<Violation> violations) {
        int minCycles = properties.getBehavior().getMinCycleRepeat();
        TrajectoryMatcher.CycleInfo cycle = TrajectoryMatcher.findCycle(actions, minCycles);
        if (cycle != null) {
            List<String> readable = cycle.getActions().stream()
                    .map(TrajectorySteps::displayName)
                    .collect(java.util.stream.Collectors.toList());
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("loop_kind", KIND_CYCLE);
            evidence.put("cycle", readable);
            evidence.put("repeats", cycle.getRepeats());
            violations.add(new Violation("cycle detected: " + readable
                    + " repeated " + cycle.getRepeats() + " times", null, evidence));
        }
    }

    private void detectBudgetOverrun(List<String> actions, List<Violation> violations) {
        int maxSteps = properties.getBehavior().getMaxSteps();
        if (actions.size() >= maxSteps) {
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("loop_kind", KIND_BUDGET_OVERRUN);
            evidence.put("actual_steps", actions.size());
            evidence.put("max_steps", maxSteps);
            violations.add(new Violation("behavior budget overrun: " + actions.size()
                    + " steps >= " + maxSteps, null, evidence));
        }
    }
}
