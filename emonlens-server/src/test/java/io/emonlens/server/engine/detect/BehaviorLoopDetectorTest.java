package io.emonlens.server.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.SpanEventEntity;

/**
 * 内置行为环检测：未知即不安全的核心防线，覆盖三种检测语义。
 */
class BehaviorLoopDetectorTest {

    private SpanEventEntity tool(String name, int second) {
        SpanEventEntity span = new SpanEventEntity();
        span.setSpanId("s-" + second);
        span.setSpanType("tool");
        span.setToolName(name);
        span.setStartTime(LocalDateTime.of(2026, 8, 30, 10, 0, second));
        span.setStatus("OK");
        return span;
    }

    private SpanEventEntity toolWithInput(String name, String input, int second) {
        SpanEventEntity span = tool(name, second);
        span.setInputText(input);
        return span;
    }

    private BehaviorLoopDetector detector() {
        EmonLensProperties properties = new EmonLensProperties();
        properties.initInstanceId();
        return new BehaviorLoopDetector(properties);
    }

    @Test
    void detectsExactRepeat() {
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(tool("search", 1));
        spans.add(tool("search", 2));
        spans.add(tool("search", 3));

        List<Violation> violations = detector().detect(spans);

        assertThat(violations).anySatisfy(v -> {
            assertThat(v.getEvidence()).containsEntry("loop_kind", BehaviorLoopDetector.KIND_EXACT_REPEAT);
            assertThat(v.getEvidence()).containsEntry("repeat_count", 3);
        });
    }

    @Test
    void doesNotFlagShortRun() {
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(tool("search", 1));
        spans.add(tool("search", 2));
        spans.add(tool("query", 3));

        assertThat(detector().detect(spans)).isEmpty();
    }

    @Test
    void detectsAlternatingCycle() {
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(tool("search", 1));
        spans.add(tool("query", 2));
        spans.add(tool("search", 3));
        spans.add(tool("query", 4));

        List<Violation> violations = detector().detect(spans);

        assertThat(violations).anySatisfy(v -> {
            assertThat(v.getEvidence()).containsEntry("loop_kind", BehaviorLoopDetector.KIND_CYCLE);
            Map<String, Object> evidence = v.getEvidence();
            assertThat(evidence.get("cycle")).isEqualTo(List.of("search", "query"));
        });
    }

    @Test
    void detectsBudgetOverrun() {
        List<SpanEventEntity> spans = new ArrayList<>();
        EmonLensProperties properties = new EmonLensProperties();
        properties.getBehavior().setMaxSteps(5);
        for (int i = 0; i < 5; i++) {
            spans.add(tool("tool_" + i, i + 1));
        }
        BehaviorLoopDetector detector = new BehaviorLoopDetector(properties);

        List<Violation> violations = detector.detect(spans);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getEvidence()).containsEntry("loop_kind", BehaviorLoopDetector.KIND_BUDGET_OVERRUN));
    }

    @Test
    void sameToolDifferentInputNotFlagged() {
        // CLI Agent 连续执行不同命令属正常模式：同工具但输入不同不算重复（误报修复）
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(toolWithInput("Bash", "{\"command\":\"git status\"}", 1));
        spans.add(toolWithInput("Bash", "{\"command\":\"ls -la\"}", 2));
        spans.add(toolWithInput("Bash", "{\"command\":\"rg foo\"}", 3));
        spans.add(toolWithInput("Bash", "{\"command\":\"cat a.txt\"}", 4));

        assertThat(detector().detect(spans)).isEmpty();
    }

    @Test
    void sameToolIdenticalInputStillFlagged() {
        // 真正的重复调用（相同工具 + 相同输入）仍应检出
        List<SpanEventEntity> spans = new ArrayList<>();
        String same = "{\"command\":\"curl http://api/x\"}";
        spans.add(toolWithInput("Bash", same, 1));
        spans.add(toolWithInput("Bash", same, 2));
        spans.add(toolWithInput("Bash", same, 3));

        List<Violation> violations = detector().detect(spans);
        assertThat(violations).anySatisfy(v -> {
            assertThat(v.getEvidence()).containsEntry("loop_kind", BehaviorLoopDetector.KIND_EXACT_REPEAT);
            assertThat(v.getEvidence()).containsEntry("action", "Bash");
        });
    }
}
