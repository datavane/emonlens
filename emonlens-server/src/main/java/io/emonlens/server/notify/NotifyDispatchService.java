package io.emonlens.server.notify;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.EventEntity;
import io.emonlens.server.storage.entity.NotifyRecordEntity;
import io.emonlens.server.storage.entity.NotifySenderEntity;
import io.emonlens.server.storage.entity.NotifyTemplateEntity;
import io.emonlens.server.storage.mapper.NotifyRecordMapper;
import io.emonlens.server.storage.mapper.NotifySenderMapper;
import io.emonlens.server.storage.mapper.NotifyTemplateMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 通知派发：把引擎事件渲染为通知并经渠道发送。
 * 从事件认领消费（EventConsumeService fan-out）；任一渠道失败仅记录 et_notify_record=FAILED，
 * 不抛出（避免事件级重试放大通知）；连续失败累计 retry_count，由运维按记录处置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyDispatchService {

    private final NotifyTemplateMapper notifyTemplateMapper;
    private final NotifySenderMapper notifySenderMapper;
    private final NotifyRecordMapper notifyRecordMapper;
    private final List<NotifyChannel> channels;
    private final EmonLensProperties properties;
    private final ObjectMapper objectMapper;

    /** 处理一条引擎事件：按事件类型/严重级挑模板 → 逐启用渠道发送。 */
    public void dispatch(EventEntity event) {
        if (!properties.getNotify().isDispatchEnabled()) {
            return;
        }
        String eventType = event.getEventType();
        if (!isNotifiable(eventType, payloadSeverity(event.getPayload()))) {
            return;
        }
        int sent = 0;
        int failed = 0;
        for (NotifySenderEntity sender : notifySenderMapper.selectList(
                Wrappers.<NotifySenderEntity>lambdaQuery()
                        .eq(NotifySenderEntity::getWorkspaceId, event.getWorkspaceId())
                        .eq(NotifySenderEntity::getStatus, "ACTIVE"))) {
            NotifyTemplateEntity template = templateFor(sender.getChannel(), eventType, event.getWorkspaceId());
            if (template == null) {
                continue;
            }
            if (dispatchToChannel(event, sender, template)) {
                sent++;
            } else {
                failed++;
            }
        }
        // 有渠道失败即外抛（EventConsumeService 按退避重投；SUCCESS 渠道被幂等跳过，仅重发失败渠道）；
        // 超过 maxRetry 事件置 DEAD，死信可从 et_event 排查
        if (failed > 0) {
            throw new IllegalStateException("notify delivery failed on " + failed + "/"
                    + (sent + failed) + " channels, event=" + event.getId());
        }
    }

    /** @return true=本渠道发送成功（含历史 SUCCESS 幂等跳过）；false=失败（可重试） */
    private boolean dispatchToChannel(EventEntity event, NotifySenderEntity sender,
                                      NotifyTemplateEntity template) {
        // 幂等：SUCCESS 记录跳过（事件重试不重复发送）；FAILED 记录复用更新（重试次数 +1）
        NotifyRecordEntity existing = notifyRecordMapper.selectOne(Wrappers.<NotifyRecordEntity>lambdaQuery()
                .eq(NotifyRecordEntity::getEventId, event.getId())
                .eq(NotifyRecordEntity::getSenderId, sender.getId())
                .orderByDesc(NotifyRecordEntity::getCreatedAt)
                .last("LIMIT 1"));
        if (existing != null && NotifyRecordEntity.STATUS_SUCCESS.equals(existing.getStatus())) {
            log.debug("notify already dispatched, event={}, sender={}", event.getId(), sender.getId());
            return true;
        }
        Map<String, Object> variables = variablesOf(event);
        String subject = NotifyTemplateRenderer.render(template.getSubject(), variables);
        String content = NotifyTemplateRenderer.render(template.getContent(), variables);
        NotifyRecordEntity record = existing != null ? existing : new NotifyRecordEntity();
        if (existing == null) {
            record.setWorkspaceId(event.getWorkspaceId());
            record.setEventId(event.getId());
            record.setEventType(event.getEventType());
            record.setChannel(sender.getChannel());
            record.setSenderId(sender.getId());
            record.setCreatedAt(LocalDateTime.now());
            record.setRetryCount(0);
        } else {
            record.setRetryCount((record.getRetryCount() == null ? 0 : record.getRetryCount()) + 1);
            record.setError(null);
        }
        record.setSubject(subject);
        record.setContent(content);
        boolean delivered = false;
        try {
            NotifyChannel channel = channels.stream()
                    .filter(c -> c.channel().equals(sender.getChannel()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no channel impl: " + sender.getChannel()));
            String target = channel.send(record, sender.getConfig(), subject, content, variables);
            record.setTarget(target);
            record.setStatus(NotifyRecordEntity.STATUS_SUCCESS);
            delivered = true;
            log.info("notify sent, channel={}, event={}, retryCount={}",
                    sender.getChannel(), event.getId(), record.getRetryCount());
        } catch (Exception e) {
            record.setStatus(NotifyRecordEntity.STATUS_FAILED);
            record.setError(e.getMessage() == null ? "" : e.getMessage().substring(0,
                    Math.min(512, e.getMessage().length())));
            log.warn("notify send failed, channel={}, event={}, retryCount={}: {}",
                    sender.getChannel(), event.getId(), record.getRetryCount(), e.getMessage());
        }
        if (existing == null) {
            notifyRecordMapper.insert(record);
        } else {
            notifyRecordMapper.updateById(record);
        }
        return delivered;
    }

    /** only severity >= HIGH 的 finding 事件才通知（防风暴）；Incident 全通知 */
    private boolean isNotifiable(String eventType, String severity) {
        if (eventType.startsWith("INCIDENT_")) {
            return true;
        }
        return "FINDING_CREATED".equals(eventType)
                && ("CRITICAL".equals(severity) || "HIGH".equals(severity));
    }

    private String payloadSeverity(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload == null ? "{}" : payload);
            return node.path("severity").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, Object> variablesOf(EventEntity event) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("agent", event.getAgentKey());
        variables.put("eventType", event.getEventType());
        variables.put("occurredAt", LocalDateTime.now().toString());
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload() == null ? "{}" : event.getPayload());
            variables.put("severity", payload.path("severity").asText(""));
            variables.put("title", payload.path("title").asText(""));
            variables.put("status", payload.path("status").asText(""));
            variables.put("traceId", payload.path("traceId").asText(""));
            variables.put("findingCount", payload.path("findingCount").asInt(0));
        } catch (Exception ignored) {
            // payload 非法时变量留空
        }
        return variables;
    }

    private NotifyTemplateEntity templateFor(String channel, String eventType, String workspaceId) {
        // 事件类型 → 模板 code 约定：incident.incident.created → tpl-incident 等；此处按 channel+事件类型前缀匹配
        List<NotifyTemplateEntity> templates = notifyTemplateMapper.selectList(
                Wrappers.<NotifyTemplateEntity>lambdaQuery()
                        .eq(NotifyTemplateEntity::getWorkspaceId, workspaceId)
                        .eq(NotifyTemplateEntity::getChannel, channel)
                        .eq(NotifyTemplateEntity::getStatus, "ACTIVE"));
        return templates.stream()
                .filter(t -> eventType.toLowerCase().startsWith(templateEventPrefix(t.getCode())))
                .findFirst()
                .orElse(templates.stream()
                        .filter(t -> t.getCode().endsWith("-default"))
                        .findFirst()
                        .orElse(null));
    }

    private String templateEventPrefix(String templateCode) {
        // 模板 code 形如 incident-default / finding-default / incident-escalated
        int dash = templateCode.indexOf('-');
        return dash < 0 ? templateCode : templateCode.substring(0, dash);
    }
}
