package io.emonlens.server.audit;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.CurrentUser;
import io.emonlens.server.storage.entity.AuditLogEntity;
import io.emonlens.server.storage.mapper.AuditLogMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    /** 开放 API / 后台任务等无登录上下文动作审计，actor 使用调用方标识。 */
    public void recordSystem(String workspaceId, String actor, String action,
                             String resourceType, String resourceId, Object detail) {
        try {
            AuditLogEntity entity = new AuditLogEntity();
            entity.setUserId("open-api");
            entity.setUsername(actor == null || actor.isBlank() ? "open-api" : actor);
            entity.setWorkspaceId(workspaceId);
            entity.setAction(action);
            entity.setResourceType(resourceType);
            entity.setResourceId(resourceId);
            entity.setDetail(detail == null ? null : objectMapper.writeValueAsString(detail));
            entity.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            log.error("system audit record failed, action={}, workspace={}", action, workspaceId, e);
        }
    }

    public void record(String action, String resourceType, String resourceId, Object detail) {
        try {
            AuditLogEntity entity = new AuditLogEntity();
            CurrentUser user = CurrentContext.require();
            entity.setUserId(user.getUserId());
            entity.setUsername(user.getUsername());
            entity.setWorkspaceId(user.getWorkspaceId());
            entity.setAction(action);
            entity.setResourceType(resourceType);
            entity.setResourceId(resourceId);
            entity.setDetail(detail == null ? null : objectMapper.writeValueAsString(detail));
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes) {
                ServletRequestAttributes attrs =
                        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                entity.setRequestId(attrs.getRequest().getHeader("X-Em-Trace-Id"));
                entity.setClientIp(attrs.getRequest().getRemoteAddr());
            }
            entity.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            // 审计失败不阻断业务，但必须留痕日志
            log.error("audit record failed, action={}", action, e);
        }
    }
}
