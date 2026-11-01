package io.emonlens.server.engine.detect;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.stream.Collectors;

import io.emonlens.server.storage.entity.SpanEventEntity;

/**
 * 轨迹步骤视图：把 span 流水压缩为"动作序列"，供 Trajectory/Behavior 检测器使用。
 */
public final class TrajectorySteps {

    /** 参与轨迹语义的 span 类型 */
    static final List<String> STEP_SPAN_TYPES =
            List.of("tool", "approval", "guardrail", "business_action");

    public static List<String> stepSpanTypes() {
        return STEP_SPAN_TYPES;
    }

    private TrajectorySteps() {
    }

    public static boolean isStep(SpanEventEntity span) {
        return STEP_SPAN_TYPES.contains(span.getSpanType());
    }

    /** 步骤动作名：优先 tool_name，其次 span_name；都缺失时回退 span_type，避免 null 进入循环比较 */
    public static String actionName(SpanEventEntity span) {
        if (span.getToolName() != null && !span.getToolName().isEmpty()) {
            return span.getToolName();
        }
        if (span.getSpanName() != null && !span.getSpanName().isEmpty()) {
            return span.getSpanName();
        }
        return span.getSpanType() == null || span.getSpanType().isEmpty() ? "unknown" : span.getSpanType();
    }

    public static List<String> actionSequence(List<SpanEventEntity> spans) {
        return spans.stream()
                .filter(TrajectorySteps::isStep)
                .map(TrajectorySteps::actionName)
                .collect(Collectors.toList());
    }

    /**
     * 带输入指纹的动作序列（循环/重复检测专用）：同一工具但参数不同视为不同动作
     * （CLI Agent 连续执行不同 Bash 命令是正常模式，仅凭工具名会大量误报）。
     * inputText 为空/未采集时回退为纯动作名（保持旧行为）。
     */
    public static List<String> actionKeySequence(List<SpanEventEntity> spans) {
        return spans.stream()
                .filter(TrajectorySteps::isStep)
                .map(TrajectorySteps::actionKey)
                .collect(Collectors.toList());
    }

    /** 动作键：动作名 + 输入短指纹 */
    public static String actionKey(SpanEventEntity span) {
        String name = actionName(span);
        String input = span.getInputText();
        if (input == null || input.isEmpty()) {
            return name;
        }
        return name + "#" + shortHash(input);
    }

    /** 从动作键还原可读动作名（证据展示用） */
    public static String displayName(String actionKey) {
        int idx = actionKey.lastIndexOf('#');
        return idx > 0 ? actionKey.substring(0, idx) : actionKey;
    }

    private static String shortHash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
