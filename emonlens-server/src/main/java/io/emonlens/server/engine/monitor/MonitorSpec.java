package io.emonlens.server.engine.monitor;

import java.util.List;

import lombok.Data;

/**
 * Monitor DSL（存储为 et_monitor.spec_json）。
 * VALIDATION：spanType 过滤 + SpEL where 表达式；
 * TRAJECTORY：规则数组（requires_before / occurs_without / max_steps / no_cycle）。
 */
@Data
public class MonitorSpec {

    private String kind;

    /** VALIDATION 可选：仅对指定 span_type 生效 */
    private String spanType;

    /** VALIDATION：SpEL 布尔表达式，true 判违规（V1.1 §12.2 where 语义） */
    private String where;

    /** TRAJECTORY：序列规则 */
    private List<RuleSpec> rules;

    @Data
    public static class RuleSpec {

        public static final String TYPE_REQUIRES_BEFORE = "requires_before";
        public static final String TYPE_OCCURS_WITHOUT = "occurs_without";
        public static final String TYPE_MAX_STEPS = "max_steps";
        public static final String TYPE_NO_CYCLE = "no_cycle";

        private String type;
        private String action;
        private List<String> required;
        private List<String> forbidden;
        private Integer limit;
        private Integer minCycles;
    }
}
