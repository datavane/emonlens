package io.emonlens.server.notify;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 通知模板变量渲染（{{var}} 占位）。 */
public final class NotifyTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*\\}\\}");

    private NotifyTemplateRenderer() {
    }

    /**
     * 单遍替换：按模板中出现顺序逐个解析占位符，变量值里即使包含 <code>{{xxx}}</code>
     * 也不会被再次展开（防止“标题值注入其他占位符”的模板注入）。
     * 未提供/未知的变量渲染为空，不把占位符发给用户。
     */
    public static String render(String template, Map<String, Object> variables) {
        if (template == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            Object value = variables == null ? null : variables.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
