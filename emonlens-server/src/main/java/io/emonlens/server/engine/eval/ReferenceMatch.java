package io.emonlens.server.engine.eval;

/**
 * 参考答案精确匹配（EXPRESSION 预设）。
 * 只统一换行并去除首尾空白，保留大小写与内部内容；不处理同义答案、结构化字段或数值容差。
 */
public final class ReferenceMatch {

    public static final String PRESET_KEY = "reference-exact-match";
    /** 预设写入 passWhen 的表达式；变量 reference_match 由 EvaluationRunService 注入 */
    public static final String PRESET_PASS_WHEN = "reference_match.exact(candidate_output, expected_output)";

    public ReferenceMatch() {
    }

    /** candidate 与 expected 规范化后完全相等。任一为空返回 false，由调用方先行判 UNKNOWN。 */
    public boolean exact(String candidate, String expected) {
        if (candidate == null || expected == null) {
            return false;
        }
        return normalize(candidate).equals(normalize(expected));
    }

    /** CRLF/CR 统一为 LF，再去除首尾空白；内部空白与大小写保持不变。 */
    public static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
