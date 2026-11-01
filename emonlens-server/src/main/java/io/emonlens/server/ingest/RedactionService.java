package io.emonlens.server.ingest;

import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import io.emonlens.common.Sha256Utils;

/**
 * 规则脱敏（V1.1 §29.1 子集）：手机号/邮箱/身份证/Secret 凭据掩码 + 长度截断 + hash。
 */
@Service
public class RedactionService {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern SECRET = Pattern.compile("(?i)(sk-[A-Za-z0-9]{16,}|Bearer\\s+[A-Za-z0-9._-]{16,}|AKIA[0-9A-Z]{16})");

    /** 掩码并截断；返回处理后的正文 */
    public String redact(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        String masked = text;
        masked = SECRET.matcher(masked).replaceAll("***REDACTED-SECRET***");
        masked = ID_CARD.matcher(masked).replaceAll("***REDACTED-ID***");
        masked = PHONE.matcher(masked).replaceAll("***REDACTED-PHONE***");
        masked = EMAIL.matcher(masked).replaceAll("***REDACTED-EMAIL***");
        return masked.length() > maxLength ? masked.substring(0, maxLength) : masked;
    }

    /** SHA-256 内容指纹（HASHED 模式与元信息记录用） */
    public String hash(String text) {
        return Sha256Utils.hash(text == null ? "" : text);
    }
}
