package io.emonlens.server.notify;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.api.dto.PageVO;
import io.emonlens.server.storage.entity.NotifyRecordEntity;
import io.emonlens.server.storage.entity.NotifySenderEntity;
import io.emonlens.server.storage.entity.NotifyTemplateEntity;
import io.emonlens.server.storage.mapper.NotifyRecordMapper;
import io.emonlens.server.storage.mapper.NotifySenderMapper;
import io.emonlens.server.storage.mapper.NotifyTemplateMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 通知配置与发送记录（工作空间内隔离）。 */
@Slf4j
@RestController
@RequestMapping("/api/v1/notify")
@RequiredArgsConstructor
public class NotifyConfigController {

    private final NotifyTemplateMapper notifyTemplateMapper;
    private final NotifySenderMapper notifySenderMapper;
    private final NotifyRecordMapper notifyRecordMapper;
    private final List<NotifyChannel> channels;

    // ---- 渠道 ----
    @GetMapping("/senders")
    @RequiresPermission(Permissions.SYS_NOTIFY_MANAGE)
    public ApiResult<List<NotifySenderEntity>> senders() {
        return ApiResult.ok(notifySenderMapper.selectList(Wrappers.<NotifySenderEntity>lambdaQuery()
                .eq(NotifySenderEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                .orderByDesc(NotifySenderEntity::getCreatedAt)));
    }

    @PostMapping("/senders")
    @RequiresPermission(Permissions.SYS_NOTIFY_MANAGE)
    @Audit(action = "notify.sender.create", resourceType = "notify-sender")
    public ApiResult<NotifySenderEntity> createSender(@RequestBody NotifySenderEntity body) {
        body.setWorkspaceId(CurrentContext.require().getWorkspaceId());
        body.setStatus("ACTIVE");
        body.setCreatedAt(LocalDateTime.now());
        body.setUpdatedAt(LocalDateTime.now());
        notifySenderMapper.insert(body);
        return ApiResult.ok(body);
    }

    @DeleteMapping("/senders/{id}")
    @RequiresPermission(Permissions.SYS_NOTIFY_MANAGE)
    @Audit(action = "notify.sender.delete", resourceType = "notify-sender")
    public ApiResult<Void> deleteSender(@PathVariable String id) {
        NotifySenderEntity sender = notifySenderMapper.selectById(id);
        if (sender == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "sender not found");
        }
        WorkspaceGuardOwnedCheck(sender.getWorkspaceId());
        notifySenderMapper.deleteById(id);
        return ApiResult.ok(null);
    }

    private void WorkspaceGuardOwnedCheck(String ws) {
        if (!CurrentContext.require().getWorkspaceId().equals(ws)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "resource not found");
        }
    }

    // ---- 模板 ----
    @GetMapping("/templates")
    @RequiresPermission(Permissions.SYS_NOTIFY_MANAGE)
    public ApiResult<List<NotifyTemplateEntity>> templates() {
        return ApiResult.ok(notifyTemplateMapper.selectList(Wrappers.<NotifyTemplateEntity>lambdaQuery()
                .eq(NotifyTemplateEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                .orderByAsc(NotifyTemplateEntity::getCode)));
    }

    @PostMapping("/templates")
    @RequiresPermission(Permissions.SYS_NOTIFY_MANAGE)
    @Audit(action = "notify.template.create", resourceType = "notify-template")
    public ApiResult<NotifyTemplateEntity> createTemplate(@RequestBody NotifyTemplateEntity body) {
        body.setWorkspaceId(CurrentContext.require().getWorkspaceId());
        body.setStatus("ACTIVE");
        body.setCreatedAt(LocalDateTime.now());
        body.setUpdatedAt(LocalDateTime.now());
        notifyTemplateMapper.insert(body);
        return ApiResult.ok(body);
    }

    @PutMapping("/templates/{id}")
    @RequiresPermission(Permissions.SYS_NOTIFY_MANAGE)
    @Audit(action = "notify.template.update", resourceType = "notify-template")
    public ApiResult<NotifyTemplateEntity> updateTemplate(@PathVariable String id,
                                                          @RequestBody NotifyTemplateEntity body) {
        NotifyTemplateEntity existing = notifyTemplateMapper.selectById(id);
        if (existing == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "template not found");
        }
        if (!CurrentContext.require().getWorkspaceId().equals(existing.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "template not found");
        }
        existing.setSubject(body.getSubject());
        existing.setContent(body.getContent());
        existing.setUpdatedAt(LocalDateTime.now());
        notifyTemplateMapper.updateById(existing);
        return ApiResult.ok(existing);
    }

    // ---- 发送记录 ----
    @GetMapping("/records")
    @RequiresPermission(Permissions.SYS_NOTIFY_MANAGE)
    public ApiResult<PageVO<NotifyRecordEntity>> records(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNumber,
            @RequestParam(defaultValue = "20") int pageSize) {
        LambdaQueryWrapper<NotifyRecordEntity> wrapper = Wrappers.lambdaQuery(NotifyRecordEntity.class)
                .eq(NotifyRecordEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                .eq(channel != null && !channel.isEmpty(), NotifyRecordEntity::getChannel, channel)
                .eq(status != null && !status.isEmpty(), NotifyRecordEntity::getStatus, status)
                .orderByDesc(NotifyRecordEntity::getCreatedAt);
        Page<NotifyRecordEntity> page = notifyRecordMapper.selectPage(PageVO.safePage(pageNumber, pageSize), wrapper);
        return ApiResult.ok(PageVO.from(page, page.getRecords().stream()
                .map(r -> r).collect(Collectors.toList())));
    }

    /** 渠道测试发送（Langfuse 通知渠道 test 对标）：用示例 Incident 变量渲染并发送，不写发送记录 */
    @PostMapping("/senders/{id}/test")
    @RequiresPermission(Permissions.SYS_NOTIFY_MANAGE)
    @io.emonlens.server.audit.Audit(action = "notify.sender.test", resourceType = "notify-sender")
    public ApiResult<Map<String, Object>> testSender(@PathVariable String id) {
        NotifySenderEntity sender = notifySenderMapper.selectById(id);
        if (sender == null || !sender.getWorkspaceId().equals(CurrentContext.require().getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "sender not found");
        }
        NotifyTemplateEntity tpl = notifyTemplateMapper.selectList(Wrappers.<NotifyTemplateEntity>lambdaQuery()
                        .eq(NotifyTemplateEntity::getWorkspaceId, sender.getWorkspaceId())
                        .eq(NotifyTemplateEntity::getChannel, sender.getChannel())
                        .orderByDesc(NotifyTemplateEntity::getCreatedAt))
                .stream().findFirst().orElse(null);
        Map<String, Object> variables = new HashMap<>();
        variables.put("agent", "test-agent");
        variables.put("eventType", "INCIDENT_CREATED");
        variables.put("occurredAt", LocalDateTime.now().toString());
        variables.put("severity", "HIGH");
        variables.put("title", "【测试】这是一条测试告警");
        variables.put("status", "OPEN");
        variables.put("traceId", "test-trace-000");
        variables.put("findingCount", 1);
        String subject = NotifyTemplateRenderer.render(tpl == null ? "EmonLens 测试通知" : tpl.getSubject(), variables);
        String content = NotifyTemplateRenderer.render(
                tpl == null ? "Agent {{agent}} 触发 {{severity}} 级事件：{{title}}（trace: {{traceId}}）" : tpl.getContent(),
                variables);
        NotifyRecordEntity record = new NotifyRecordEntity();
        record.setWorkspaceId(sender.getWorkspaceId());
        record.setEventType("TEST");
        record.setChannel(sender.getChannel());
        record.setSenderId(sender.getId());
        record.setSubject(subject);
        record.setContent(content);
        record.setCreatedAt(LocalDateTime.now());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subject", subject);
        out.put("content", content);
        try {
            NotifyChannel channel = channels.stream()
                    .filter(c -> c.channel().equals(sender.getChannel()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("无该渠道实现: " + sender.getChannel()));
            String target = channel.send(record, sender.getConfig(), subject, content, variables);
            out.put("ok", true);
            out.put("target", target);
        } catch (Exception e) {
            // 不回显底层异常/状态码：test 端点可被当探测内网的 oracle（详情只入服务端日志）
            log.warn("notify sender test failed: sender={}, channel={}", sender.getId(), sender.getChannel(), e);
            out.put("ok", false);
            out.put("error", "发送失败，详情见服务端日志");
        }
        return ApiResult.ok(out);
    }
}
