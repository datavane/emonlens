package io.emonlens.server.api;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.engine.event.EventService;
import io.emonlens.server.storage.entity.EventEntity;

import lombok.RequiredArgsConstructor;

/**
 * 事件死信管理（告警链路可靠性闭环的人工恢复出口）：
 * 通知/Webhook 重试超限置 DEAD 的事件在此可见并可重新入队（重试计数清零，任何实例下一轮认领）。
 */
@RestController
@RequestMapping("/api/v1/event-dead")
@RequiredArgsConstructor
public class EventDeadController {

    private final EventService eventService;

    /** 死信列表：分页 + 事件类型过滤（运维可只看通知/Webhook 类死信） */
    @GetMapping
    @RequiresPermission(Permissions.INCIDENT_MANAGE)
    public ApiResult<Map<String, Object>> list(@RequestParam(required = false) String eventType,
                                               @RequestParam(defaultValue = "1") int pageNumber,
                                               @RequestParam(defaultValue = "20") int pageSize) {
        String ws = CurrentContext.require().getWorkspaceId();
        int size = Math.max(1, Math.min(pageSize, 50));
        int offset = (Math.max(pageNumber, 1) - 1) * size;
        List<Map<String, Object>> rows = eventService.listDead(ws, eventType, offset, size).stream().map(e -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("eventType", e.getEventType());
            m.put("agentKey", e.getAgentKey());
            m.put("retryCount", e.getRetryCount());
            m.put("createdAt", e.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("records", rows);
        out.put("total", eventService.countDead(ws, eventType));
        return ApiResult.ok(out);
    }

    /** 重新入队：DEAD → PENDING（重试计数清零），由任意实例下一轮认领重处理 */
    @PostMapping("/{id}/requeue")
    @RequiresPermission(Permissions.INCIDENT_MANAGE)
    @Audit(action = "event-dead.requeue", resourceType = "event")
    public ApiResult<Boolean> requeue(@PathVariable String id) {
        String ws = CurrentContext.require().getWorkspaceId();
        // 校验归属：仅允许重投本工作空间的死信
        if (!eventService.requeueDead(ws, id)) {
            throw new io.emonlens.common.EmonLensException(
                    io.emonlens.common.ErrorCode.NOT_FOUND, "dead event not found: " + id);
        }
        return ApiResult.ok(true);
    }
}
