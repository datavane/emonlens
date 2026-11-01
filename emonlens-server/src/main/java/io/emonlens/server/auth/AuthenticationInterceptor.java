package io.emonlens.server.auth;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.ApiResult;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.storage.entity.UserEntity;
import io.emonlens.server.storage.entity.UserWorkspaceEntity;
import io.emonlens.server.storage.mapper.UserMapper;
import io.emonlens.server.storage.mapper.UserWorkspaceMapper;
import io.emonlens.server.storage.mapper.RoleAuthMapper;
import io.emonlens.server.storage.entity.RoleAuthEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 控制面 JWT 认证 + 工作空间上下文 + 权限校验。
 * 工作空间取 header X-Workspace-Id（缺省用户默认工作空间），非成员 → 40301。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthenticationInterceptor implements HandlerInterceptor {

    public static final String WORKSPACE_HEADER = "X-Workspace-Id";

    private final TokenService tokenService;
    private final UserMapper userMapper;
    private final UserWorkspaceMapper userWorkspaceMapper;
    private final RoleAuthMapper roleAuthMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return reject(response, ErrorCode.UNAUTHORIZED, "missing bearer token");
        }
        var claims = tokenService.parse(authorization.substring(7));
        String userId = claims.path("sub").asText();
        UserEntity user = userMapper.selectById(userId);
        if (user == null || !UserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
            return reject(response, ErrorCode.UNAUTHORIZED, "user inactive or removed");
        }
        String workspaceId = request.getHeader(WORKSPACE_HEADER);
        if (workspaceId == null || workspaceId.isEmpty()) {
            workspaceId = user.getDefaultWorkspaceId();
        }
        List<UserWorkspaceEntity> memberships = userWorkspaceMapper.selectList(
                Wrappers.<UserWorkspaceEntity>lambdaQuery()
                        .eq(UserWorkspaceEntity::getUserId, userId)
                        .eq(UserWorkspaceEntity::getWorkspaceId, workspaceId));
        if (memberships.isEmpty()) {
            return reject(response, ErrorCode.FORBIDDEN, "当前用户不属于该工作空间");
        }
        Set<String> permissions = loadPermissions(memberships.get(0).getRoleId());
        CurrentContext.set(new CurrentUser(userId, user.getUsername(),
                user.getIsSuper() != null && user.getIsSuper() == 1, workspaceId, permissions));
        try {
            RequiresPermission required = ((HandlerMethod) handler).getMethodAnnotation(RequiresPermission.class);
            if (required == null) {
                required = ((HandlerMethod) handler).getBeanType().getAnnotation(RequiresPermission.class);
            }
            if (required != null && !CurrentContext.require().hasPermission(required.value())) {
                CurrentContext.clear();
                return reject(response, ErrorCode.FORBIDDEN, "无权限执行该操作");
            }
            return true;
        } catch (Exception e) {
            CurrentContext.clear();
            throw e;
        }
    }

    private Set<String> loadPermissions(String roleId) {
        List<RoleAuthEntity> auths = roleAuthMapper.selectList(Wrappers.<RoleAuthEntity>lambdaQuery()
                .eq(RoleAuthEntity::getRoleId, roleId));
        Set<String> permissions = new HashSet<>();
        for (RoleAuthEntity auth : auths) {
            permissions.add(auth.getPermission());
        }
        return permissions;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CurrentContext.clear();
    }

    private boolean reject(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResult.error(code, message)));
        log.debug("auth rejected: {}", message);
        return false;
    }
}
