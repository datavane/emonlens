package io.emonlens.server.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.emonlens.server.engine.monitor.MonitorSpec;
import io.emonlens.server.engine.monitor.MonitorSpec.RuleSpec;
import io.emonlens.server.storage.entity.SpanEventEntity;

/**
 * 轨迹算子语义测试：验证"支付前必须审批"类规则的核心行为。
 */
class TrajectoryMatcherTest {

    private SpanEventEntity tool(String traceId, String spanId, String name, LocalDateTime start) {
        SpanEventEntity span = new SpanEventEntity();
        span.setTraceId(traceId);
        span.setSpanId(spanId);
        span.setSpanType("tool");
        span.setToolName(name);
        span.setSpanName(name);
        span.setStartTime(start);
        span.setStatus("OK");
        return span;
    }

    private MonitorSpec specWithRules(RuleSpec... rules) {
        MonitorSpec spec = new MonitorSpec();
        List<RuleSpec> list = new ArrayList<>();
        for (RuleSpec rule : rules) {
            list.add(rule);
        }
        spec.setRules(list);
        return spec;
    }

    private LocalDateTime t(int minute) {
        return LocalDateTime.of(2026, 8, 30, 10, minute, 0);
    }

    @Test
    void requiresBeforeFailsWhenApprovalMissing() {
        // 支付动作发生了，但缺少 human_approval 前置
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(tool("tr", "s1", "check_balance", t(1)));
        spans.add(tool("tr", "s2", "execute_payment", t(2)));

        RuleSpec rule = new RuleSpec();
        rule.setType(RuleSpec.TYPE_REQUIRES_BEFORE);
        rule.setAction("execute_payment");
        rule.setRequired(List.of("check_balance", "human_approval"));

        List<Violation> violations = TrajectoryMatcher.evaluate(spans, specWithRules(rule));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getEvidence())
                .containsEntry("missing", List.of("human_approval"));
        // 违规应关联到 action 所在 span
        assertThat(violations.get(0).getSpanId()).isEqualTo("s2");
    }

    @Test
    void requiresBeforePassesWhenAllRequiredPresent() {
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(tool("tr", "s1", "check_balance", t(1)));
        spans.add(tool("tr", "s2", "human_approval", t(2)));
        spans.add(tool("tr", "s3", "execute_payment", t(3)));

        RuleSpec rule = new RuleSpec();
        rule.setType(RuleSpec.TYPE_REQUIRES_BEFORE);
        rule.setAction("execute_payment");
        rule.setRequired(List.of("check_balance", "human_approval"));

        assertThat(TrajectoryMatcher.evaluate(spans, specWithRules(rule))).isEmpty();
    }

    @Test
    void requiresBeforeSkippedWhenActionAbsent() {
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(tool("tr", "s1", "check_balance", t(1)));

        RuleSpec rule = new RuleSpec();
        rule.setType(RuleSpec.TYPE_REQUIRES_BEFORE);
        rule.setAction("execute_payment");
        rule.setRequired(List.of("human_approval"));

        assertThat(TrajectoryMatcher.evaluate(spans, specWithRules(rule))).isEmpty();
    }

    @Test
    void occursWithoutFlagsCoOccurrence() {
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(tool("tr", "s1", "execute_payment", t(1)));
        spans.add(tool("tr", "s2", "export_pii", t(2)));

        RuleSpec rule = new RuleSpec();
        rule.setType(RuleSpec.TYPE_OCCURS_WITHOUT);
        rule.setAction("execute_payment");
        rule.setForbidden(List.of("export_pii"));

        assertThat(TrajectoryMatcher.evaluate(spans, specWithRules(rule))).hasSize(1);
    }

    @Test
    void maxStepsFailsBeyondLimit() {
        List<SpanEventEntity> spans = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            spans.add(tool("tr", "s" + i, "query_" + i, t(1).plusMinutes(i)));
        }
        RuleSpec rule = new RuleSpec();
        rule.setType(RuleSpec.TYPE_MAX_STEPS);
        rule.setLimit(3);

        List<Violation> violations = TrajectoryMatcher.evaluate(spans, specWithRules(rule));
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getEvidence())
                .containsEntry("actual", 5)
                .containsEntry("limit", 3);
    }

    @Test
    void noCycleDetectsAlternatingLoop() {
        // search -> query -> search -> query -> search -> query：周期为 2，重复 3 次
        List<SpanEventEntity> spans = new ArrayList<>();
        String[] names = {"search", "query", "search", "query", "search", "query"};
        for (int i = 0; i < names.length; i++) {
            spans.add(tool("tr", "s" + i, names[i], t(1).plusSeconds(i)));
        }
        RuleSpec rule = new RuleSpec();
        rule.setType(RuleSpec.TYPE_NO_CYCLE);
        rule.setMinCycles(2);

        List<Violation> violations = TrajectoryMatcher.evaluate(spans, specWithRules(rule));
        assertThat(violations).hasSize(1);
        Map<String, Object> evidence = violations.get(0).getEvidence();
        assertThat(evidence).containsEntry("repeats", 3);
        assertThat(evidence.get("cycle")).isEqualTo(List.of("search", "query"));
    }

    @Test
    void nonStepSpansAreIgnored() {
        // llm span 不参与轨迹
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(tool("tr", "s1", "check_balance", t(1)));
        SpanEventEntity llm = tool("tr", "s2", "llm_call", t(2));
        llm.setSpanType("llm");
        llm.setToolName(null);
        spans.add(llm);
        spans.add(tool("tr", "s3", "execute_payment", t(3)));

        RuleSpec rule = new RuleSpec();
        rule.setType(RuleSpec.TYPE_REQUIRES_BEFORE);
        rule.setAction("execute_payment");
        rule.setRequired(List.of("check_balance"));

        assertThat(TrajectoryMatcher.evaluate(spans, specWithRules(rule))).isEmpty();
    }
}
