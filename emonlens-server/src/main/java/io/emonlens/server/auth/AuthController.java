package io.emonlens.server.auth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.storage.entity.UserEntity;
import io.emonlens.server.storage.entity.UserWorkspaceEntity;
import io.emonlens.server.storage.entity.WorkspaceEntity;
import io.emonlens.server.storage.mapper.RoleAuthMapper;
import io.emonlens.server.storage.entity.RoleAuthEntity;
import io.emonlens.server.storage.mapper.UserMapper;
import io.emonlens.server.storage.mapper.UserWorkspaceMapper;
import io.emonlens.server.storage.mapper.WorkspaceMapper;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserMapper userMapper;
    private final UserWorkspaceMapper userWorkspaceMapper;
    private final WorkspaceMapper workspaceMapper;
    private final RoleAuthMapper roleAuthMapper;
    private final TokenService tokenService;
    private final PwdService pwdService;
    private final LoginGuard loginGuard;
    private final io.emonlens.server.config.EmonLensProperties properties;

    @PostMapping("/login")
    public ApiResult<Map<String, Object>> login(@RequestBody LoginRequest request,
                                                HttpServletRequest httpRequest) {
        String ip = LoginGuard.clientIp(httpRequest, properties);
        loginGuard.check(ip, request.getUsername());
        UserEntity user = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, request.getUsername()));
        if (user == null || !pwdService.verify(request.getPassword(), user.getPasswordHash())) {
            loginGuard.onFailure(ip, request.getUsername());
            throw new EmonLensException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!UserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new EmonLensException(ErrorCode.UNAUTHORIZED, "账号已锁定，请联系管理员");
        }
        loginGuard.onSuccess(ip, request.getUsername());
        List<UserWorkspaceEntity> memberships = userWorkspaceMapper.selectList(
                Wrappers.<UserWorkspaceEntity>lambdaQuery().eq(UserWorkspaceEntity::getUserId, user.getId()));
        if (memberships.isEmpty()) {
            throw new EmonLensException(ErrorCode.FORBIDDEN, "账号未分配工作空间");
        }
        UserWorkspaceEntity membership = memberships.stream()
                .filter(m -> m.getWorkspaceId().equals(user.getDefaultWorkspaceId()))
                .findFirst().orElse(memberships.get(0));
        WorkspaceEntity workspace = workspaceMapper.selectById(membership.getWorkspaceId());
        String token = tokenService.issue(user.getId(), user.getUsername(), workspace.getId(), 8 * 3600);
        Set<String> permissions = loadPermissions(membership.getRoleId());
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("user", Map.of("id", user.getId(), "username", user.getUsername(),
                "name", user.getName() == null ? user.getUsername() : user.getName(),
                "superUser", user.getIsSuper() != null && user.getIsSuper() == 1));
        data.put("workspace", Map.of("id", workspace.getId(), "name", workspace.getName()));
        data.put("permissions", new ArrayList<>(permissions));
        return ApiResult.ok(data);
    }

    /** 当前登录身份：GET /api/v1/auth/me（前端刷新后恢复会话） */
    @GetMapping("/me")
    public ApiResult<Map<String, Object>> me() {
        CurrentUser user = CurrentContext.require();
        WorkspaceEntity workspace = workspaceMapper.selectById(user.getWorkspaceId());
        Map<String, Object> data = new HashMap<>();
        data.put("user", Map.of("id", user.getUserId(), "username", user.getUsername(), "superUser", user.isSuperUser()));
        data.put("workspace", Map.of("id", user.getWorkspaceId(),
                "name", workspace == null ? user.getWorkspaceId() : workspace.getName()));
        data.put("permissions", new ArrayList<>(user.getPermissions()));
        return ApiResult.ok(data);
    }

    /** 登出：吊销当前 JWT（黑名单落库，token 立即失效） */
    @PostMapping("/logout")
    public ApiResult<Void> logout(HttpServletRequest request) {
        CurrentContext.require();
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            tokenService.revoke(authorization.substring(7));
        }
        return ApiResult.ok(null);
    }

    private Set<String> loadPermissions(String roleId) {
        Set<String> permissions = new java.util.HashSet<>();
        for (RoleAuthEntity auth : roleAuthMapper.selectList(Wrappers.<RoleAuthEntity>lambdaQuery()
                .eq(RoleAuthEntity::getRoleId, roleId))) {
            permissions.add(auth.getPermission());
        }
        return permissions;
    }

    @lombok.Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}
