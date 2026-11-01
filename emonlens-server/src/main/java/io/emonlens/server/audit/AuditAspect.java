package io.emonlens.server.audit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @Audit 切面：方法成功返回后写审计（失败/异常不写，避免半操作留痕）。
 * detail 记录请求参数快照（脱敏后的 JSON，截断 2KB），使设置类变更可追溯"改了什么"。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private static final int MAX_DETAIL_LENGTH = 2048;

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @AfterReturning("@annotation(audit)")
    public void afterReturning(JoinPoint joinPoint, Audit audit) {
        Object resourceId = null;
        Object[] args = joinPoint.getArgs();
        if (args.length > 0 && args[0] instanceof String) {
            resourceId = (String) args[0];
        }
        auditService.record(audit.action(), audit.resourceType(),
                resourceId == null ? null : String.valueOf(resourceId), snapshotArgs(args));
    }

    /** 参数快照：跳过 Servlet 类型；密钥/口令类字段掩码；序列化后截断 2KB。
     *  返回反序列化后的对象（而非字符串），由 AuditService 统一序列化一次，避免双重编码。 */
    Object snapshotArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        try {
            Object[] safe = IntStream.range(0, args.length)
                    .mapToObj(i -> isWebRequest(args[i]) ? "arg" + i : args[i])
                    .toArray();
            Object sanitized = sanitize(safe, null, 0);
            String json = objectMapper.writeValueAsString(sanitized);
            // 兜底：覆盖未识别字段名 / 第三方 DTO 里的敏感值
            json = json.replaceAll("(?i)(\"(?:password|secret|token|apiKey|api_key|credential|authorization)\"\\s*:\\s*)\"[^\"]*\"", "$1\"***\"");
            if (json.length() > MAX_DETAIL_LENGTH) {
                json = json.substring(0, MAX_DETAIL_LENGTH) + "…";
            }
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.debug("audit snapshot skipped: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 递归脱敏请求快照。仅审计展示用：
     * - password/secret/token/apiKey 等字段整体掩码；
     * - config 这类“内嵌 JSON 字符串”也会解析后脱敏，避免 SMTP/Webhook 凭据明文进审计。
     */
    private Object sanitize(Object value, String field, int depth) {
        if (value == null || depth > 12) {
            return value;
        }
        if (isSensitiveField(field)) {
            return "***";
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<String, Object> safe = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                safe.put(key, sanitize(entry.getValue(), key, depth + 1));
            }
            return safe;
        }
        if (value instanceof Collection) {
            Collection<?> collection = (Collection<?>) value;
            List<Object> items = new ArrayList<>(collection.size());
            for (Object item : collection) {
                items.add(sanitize(item, field, depth + 1));
            }
            return items;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> items = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                items.add(sanitize(java.lang.reflect.Array.get(value, i), field, depth + 1));
            }
            return items;
        }
        if (value instanceof String) {
            return sanitizeEmbeddedJson((String) value, depth);
        }
        return value;
    }

    private Object sanitizeEmbeddedJson(String text, int depth) {
        String trimmed = text == null ? "" : text.trim();
        if (depth >= 12 || !(trimmed.startsWith("{") && trimmed.endsWith("}"))) {
            return text;
        }
        try {
            Object parsed = objectMapper.readValue(trimmed, Object.class);
            Object safe = sanitize(parsed, null, depth + 1);
            return objectMapper.writeValueAsString(safe);
        } catch (Exception e) {
            return text;
        }
    }

    private boolean isSensitiveField(String field) {
        if (field == null || field.isBlank()) {
            return false;
        }
        String key = field.toLowerCase().replace("_", "").replace("-", "");
        return key.contains("password") || key.contains("secret") || key.contains("token")
                || key.contains("apikey") || key.equals("authorization")
                || key.equals("privatekey") || key.equals("credential");
    }

    private boolean isWebRequest(Object arg) {
        return arg instanceof javax.servlet.ServletRequest
                || arg instanceof javax.servlet.ServletResponse
                || arg instanceof org.springframework.web.multipart.MultipartFile;
    }
}
