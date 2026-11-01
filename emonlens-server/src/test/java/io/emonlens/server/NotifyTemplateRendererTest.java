package io.emonlens.server.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 通知模板渲染红线：变量正确替换；未提供变量清空占位不泄露内部键名。
 */
class NotifyTemplateRendererTest {

    @Test
    void replacesKnownVariables() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("agent", "support-agent");
        vars.put("severity", "CRITICAL");
        String out = NotifyTemplateRenderer.render(
                "Agent {{agent}} 触发 {{severity}} 级 Incident", vars);
        assertThat(out).isEqualTo("Agent support-agent 触发 CRITICAL 级 Incident");
    }

    @Test
    void unknownVariablesAreCleared() {
        String out = NotifyTemplateRenderer.render("状态 {{status}}，内部 {{internalKey}}",
                Map.of("status", "OPEN"));
        assertThat(out).isEqualTo("状态 OPEN，内部 ");
        assertThat(out).doesNotContain("{{");
    }

    @Test
    void nullValuesRenderAsEmpty() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("agent", null);
        assertThat(NotifyTemplateRenderer.render("[{{agent}}]", vars)).isEqualTo("[]");
    }

    @Test
    void variableValueCannotInjectOtherPlaceholders() {
        // 值里带 {{severity}}：单遍替换下保持字面量，不得被后续变量再展开
        Map<String, Object> vars = new java.util.LinkedHashMap<>();
        vars.put("title", "触发 {{severity}} 联动");
        vars.put("severity", "CRITICAL");
        String out = NotifyTemplateRenderer.render("告警：{{title}} / {{severity}}", vars);
        assertThat(out).isEqualTo("告警：触发 {{severity}} 联动 / CRITICAL");
        assertThat(out).doesNotContain("CRITICAL 联动");
    }
}
