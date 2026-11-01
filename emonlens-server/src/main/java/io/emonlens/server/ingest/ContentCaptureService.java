package io.emonlens.server.ingest;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.ContentEntity;
import io.emonlens.server.storage.mapper.ContentMapper;

import lombok.RequiredArgsConstructor;

/**
 * 内容采集管线（V1.1 §6.4 content_capture_mode）：
 * NONE=不采集；HASHED=仅指纹；REDACTED=脱敏正文（推荐生产）；FULL=原文（开发/受控环境）。
 * 模式为全局配置，project/agent 级 override 由 et_ingest_key 扩展列后续承接。
 */
@Service
@RequiredArgsConstructor
public class ContentCaptureService {

    public static final String MODE_NONE = "NONE";
    public static final String MODE_HASHED = "HASHED";
    public static final String MODE_REDACTED = "REDACTED";
    public static final String MODE_FULL = "FULL";

    private final RedactionService redactionService;
    private final ContentMapper contentMapper;
    private final EmonLensProperties properties;

    /** 采集并落库 span 的内容集合（key=kind，value=正文） */
    public void capture(String workspaceId, String traceId, String spanId, Map<String, String> contents) {
        if (contents == null || contents.isEmpty()) {
            return;
        }
        String mode = properties.getContent().getCaptureMode();
        if (MODE_NONE.equals(mode)) {
            return;
        }
        int maxLength = properties.getContent().getMaxContentLength();
        for (Map.Entry<String, String> entry : contents.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            ContentEntity entity = new ContentEntity();
            entity.setWorkspaceId(workspaceId);
            entity.setTraceId(traceId);
            entity.setSpanId(spanId);
            entity.setKind(entry.getKey());
            entity.setCaptureMode(mode);
            entity.setContentHash(redactionService.hash(entry.getValue()));
            entity.setContentLength((long) entry.getValue().length());
            entity.setCreatedAt(LocalDateTime.now());
            if (MODE_HASHED.equals(mode)) {
                entity.setPreview("hash:" + entity.getContentHash());
            } else if (MODE_REDACTED.equals(mode)) {
                String redacted = redactionService.redact(entry.getValue(), maxLength);
                entity.setBody(redacted);
                entity.setPreview(redacted.substring(0, Math.min(512, redacted.length())));
            } else if (MODE_FULL.equals(mode)) {
                String full = entry.getValue().length() > maxLength
                        ? entry.getValue().substring(0, maxLength) : entry.getValue();
                entity.setBody(full);
                entity.setPreview(full.substring(0, Math.min(512, full.length())));
            } else {
                return;
            }
            try {
                contentMapper.insert(entity);
            } catch (DuplicateKeyException e) {
                // at-least-once 重试（TRACE_COMPLETED 重放）：uk_content_span_kind 保证幂等
            }
        }
    }
}
