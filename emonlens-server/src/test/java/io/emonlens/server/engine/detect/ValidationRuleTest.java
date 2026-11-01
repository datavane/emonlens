package io.emonlens.server.engine.detect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.emonlens.server.engine.monitor.MonitorSpec;
import io.emonlens.server.storage.entity.SpanEventEntity;

/**
 * Validation 规则（SpEL 沙箱）：验证模型白名单类规则的判定与上下文绑定。
 */
class ValidationRuleTest {

    private SpanEventEntity llmSpan(String modelName) {
        SpanEventEntity span = new SpanEventEntity();
        span.setTraceId("tr");
        span.setSpanId("s1");
        span.setSpanType("llm");
        span.setSpanName("call_model");
        span.setModelName(modelName);
        span.setStartTime(LocalDateTime.of(2026, 8, 30, 10, 0));
        span.setStatus("OK");
        span.setInputTokens(100L);
        return span;
    }

    private MonitorSpec validationSpec(String spanType, String where) {
        MonitorSpec spec = new MonitorSpec();
        spec.setKind("VALIDATION");
        spec.setSpanType(spanType);
        spec.setWhere(where);
        return spec;
    }

    @Test
    void flagsUnapprovedModel() {
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(llmSpan("gpt-unapproved"));

        List<Violation> violations = ValidationRule.evaluate(spans,
                validationSpec("llm", "model_name != 'approved-a' && model_name != 'approved-b'"));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getSpanId()).isEqualTo("s1");
        assertThat(violations.get(0).getEvidence()).containsEntry("where",
                "model_name != 'approved-a' && model_name != 'approved-b'");
    }

    @Test
    void passesApprovedModel() {
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(llmSpan("approved-a"));

        assertThat(ValidationRule.evaluate(spans,
                validationSpec("llm", "model_name != 'approved-a' && model_name != 'approved-b'"))).isEmpty();
    }

    @Test
    void spanTypeFilterLimitsScope() {
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(llmSpan("gpt-unapproved"));
        SpanEventEntity toolSpan = llmSpan("gpt-unapproved");
        toolSpan.setSpanType("tool");
        toolSpan.setModelName(null);
        spans.add(toolSpan);

        // 仅对 llm span 生效
        assertThat(ValidationRule.evaluate(spans, validationSpec("llm", "model_name != null")))
                .hasSize(1);
        // 不过滤时 tool span 的 model_name 为 null，表达式不为 false，不违规
        List<Violation> all = ValidationRule.evaluate(spans, validationSpec(null, "model_name != null"));
        assertThat(all).hasSize(1);
    }

    @Test
    void tokenBudgetRuleUsesContext() {
        SpanEventEntity span = llmSpan("approved-a");
        span.setOutputTokens(99999L);
        List<SpanEventEntity> spans = new ArrayList<>();
        spans.add(span);

        List<Violation> violations = ValidationRule.evaluate(spans,
                validationSpec("llm", "output_tokens != null && output_tokens > 5000"));

        assertThat(violations).hasSize(1);
    }

    // ---- UI 模板库表达式与引擎的兼容性回归（AlertsView validationTemplates）----

    @Test
    void slowCallTemplate() {
        SpanEventEntity span = llmSpan("gpt-4o-mini");
        span.setDurationMs(31000L);
        assertThat(ValidationRule.evaluate(List.of(span),
                validationSpec(null, "duration_ms > 30000"))).hasSize(1);
        span.setDurationMs(5000L);
        assertThat(ValidationRule.evaluate(List.of(span),
                validationSpec(null, "duration_ms > 30000"))).isEmpty();
    }

    @Test
    void rateLimitRegexTemplate() {
        SpanEventEntity span = llmSpan("gpt-4o-mini");
        span.setStatus("ERROR");
        span.setErrorType("UpstreamRateLimitError");
        assertThat(ValidationRule.evaluate(List.of(span),
                validationSpec("llm", "error_type matches '.*RateLimit.*'"))).hasSize(1);
        span.setErrorType("TimeoutError");
        assertThat(ValidationRule.evaluate(List.of(span),
                validationSpec("llm", "error_type matches '.*RateLimit.*'"))).isEmpty();
    }

    @Test
    void modelWhitelistCollectionTemplate() {
        SpanEventEntity span = llmSpan("gpt-3.5-turbo");
        List<Violation> violations = ValidationRule.evaluate(List.of(span),
                validationSpec("llm",
                        "model_name != null and !({'gpt-4o-mini','gpt-4o'}.contains(model_name))"));
        assertThat(violations).hasSize(1);
        span.setModelName("gpt-4o");
        assertThat(ValidationRule.evaluate(List.of(span),
                validationSpec("llm",
                        "model_name != null and !({'gpt-4o-mini','gpt-4o'}.contains(model_name))")))
                .isEmpty();
    }

    @Test
    void attributesAreExposedToWhere() {
        SpanEventEntity span = llmSpan("gpt-4o-mini");
        span.setAttributes("{\"turnTotalTokens\":150000}");
        assertThat(ValidationRule.evaluate(List.of(span),
                validationSpec("llm", "turnTotalTokens != null and turnTotalTokens > 100000"))).hasSize(1);
    }
}
