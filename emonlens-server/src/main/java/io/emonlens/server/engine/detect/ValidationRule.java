package io.emonlens.server.engine.detect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.expression.Expression;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.ReflectiveMethodExecutor;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.context.expression.MapAccessor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.engine.monitor.MonitorSpec;
import io.emonlens.server.storage.entity.SpanEventEntity;

/**
 * Validation Monitor：单 span 确定性规则（SpEL，只读沙箱）。
 * where 表达式对 span 字段上下文求值，结果为 true 即违规；上下文含全部 span 字段 +
 * attributes 展开（如 "error_type matches '.*RateLimit.*'"、
 * "!({'model-a','model-b'}.contains(model_name))"）。
 * 沙箱边界：无类型引用/构造/静态方法，仅放行 SAFE_METHODS 白名单内的实例方法。
 */
public final class ValidationRule {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final ObjectMapper ATTRIBUTES_MAPPER = new ObjectMapper();

    /** 允许在 where 中调用的实例方法（字符串/集合的确定性只读操作） */
    private static final Set<String> SAFE_METHODS = Set.of(
            "contains", "startsWith", "endsWith", "equalsIgnoreCase",
            "toLowerCase", "toUpperCase", "trim", "isEmpty", "size", "length");

    private static final MethodResolver SAFE_METHOD_RESOLVER = (context, target, name, arguments) -> {
        if (target == null || !SAFE_METHODS.contains(name)) {
            return null;
        }
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arguments.size()) {
                return (MethodExecutor) new ReflectiveMethodExecutor(m);
            }
        }
        return null;
    };

    private ValidationRule() {
    }

    public static List<Violation> evaluate(List<SpanEventEntity> spans, MonitorSpec spec) {
        List<Violation> violations = new ArrayList<>();
        if (spec.getWhere() == null || spec.getWhere().isEmpty()) {
            return violations;
        }
        Expression expression = PARSER.parseExpression(spec.getWhere());
        for (SpanEventEntity span : spans) {
            if (spec.getSpanType() != null && !spec.getSpanType().isEmpty()
                    && !spec.getSpanType().equals(span.getSpanType())) {
                continue;
            }
            Map<String, Object> context = buildContext(span);
            Boolean matched = expression.getValue(evaluationContext(), context, Boolean.class);
            if (Boolean.TRUE.equals(matched)) {
                Map<String, Object> evidence = new HashMap<>();
                evidence.put("where", spec.getWhere());
                evidence.put("span_type", span.getSpanType());
                evidence.put("span_name", span.getSpanName());
                violations.add(new Violation("validation rule failed on span " + span.getSpanId(),
                        span.getSpanId(), evidence));
            }
        }
        return violations;
    }

    private static SimpleEvaluationContext evaluationContext() {
        return SimpleEvaluationContext.forPropertyAccessors(new MapAccessor())
                .withMethodResolvers(SAFE_METHOD_RESOLVER)
                .build();
    }

    static Map<String, Object> buildContext(SpanEventEntity span) {
        Map<String, Object> context = new HashMap<>();
        context.put("trace_id", span.getTraceId());
        context.put("span_id", span.getSpanId());
        context.put("span_type", span.getSpanType());
        context.put("span_name", span.getSpanName());
        context.put("model_name", span.getModelName());
        context.put("tool_name", span.getToolName());
        context.put("status", span.getStatus());
        context.put("error_type", span.getErrorType());
        context.put("duration_ms", span.getDurationMs());
        context.put("input_tokens", span.getInputTokens());
        context.put("output_tokens", span.getOutputTokens());
        if (span.getAttributes() != null && !span.getAttributes().isEmpty()) {
            try {
                Map<String, Object> attributes = ATTRIBUTES_MAPPER.readValue(span.getAttributes(),
                        new TypeReference<Map<String, Object>>() {
                        });
                context.putAll(attributes);
            } catch (Exception ignored) {
                // attributes 非法 JSON 时不参与上下文
            }
        }
        return context;
    }
}
