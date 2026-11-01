package io.emonlens.server.sys;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.CurrentUser;
import io.emonlens.server.auth.PwdService;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.api.dto.PageVO;
import io.emonlens.server.storage.entity.UserEntity;
import io.emonlens.server.storage.entity.UserWorkspaceEntity;
import io.emonlens.server.storage.mapper.UserMapper;
import io.emonlens.server.storage.mapper.UserWorkspaceMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/sys/users")
@RequiredArgsConstructor
public class SysUserController {

    /** 密码强度（与 prod 门禁的 bootstrap 口径一致：>=8 位）；上限防超长输入打爆哈希。 */
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final int MAX_USERNAME_LENGTH = 64;

    private final UserMapper userMapper;
    private final UserWorkspaceMapper userWorkspaceMapper;
    private final PwdService pwdService;

    private static void validateUsername(String username) {
        if (username == null || username.trim().isEmpty() || username.length() > MAX_USERNAME_LENGTH) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "username 必填且不超过 " + MAX_USERNAME_LENGTH + " 字符");
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH
                || password.length() > MAX_PASSWORD_LENGTH) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "password 长度须在 " + MIN_PASSWORD_LENGTH + "-" + MAX_PASSWORD_LENGTH + " 位之间");
        }
    }

    @GetMapping
    @RequiresPermission(Permissions.SYS_USER_MANAGE)
    public ApiResult<PageVO<Map<String, Object>>> page(@RequestParam(defaultValue = "1") int pageNumber,
                                                       @RequestParam(defaultValue = "20") int pageSize,
                                                       @RequestParam(required = false) String query,
                                                       @RequestParam(required = false) String status) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        List<UserWorkspaceEntity> memberships = userWorkspaceMapper.selectList(
                Wrappers.<UserWorkspaceEntity>lambdaQuery().eq(UserWorkspaceEntity::getWorkspaceId, workspaceId));
        Map<String, String> roleByUser = new HashMap<>();
        memberships.forEach(m -> roleByUser.put(m.getUserId(), m.getRoleId()));
        String kw = query == null ? "" : query.trim();
        Page<UserEntity> page = userMapper.selectPage(PageVO.safePage(pageNumber, pageSize),
                Wrappers.<UserEntity>lambdaQuery().in(UserEntity::getId, roleByUser.keySet())
                        .and(!kw.isEmpty(), w -> w.like(UserEntity::getUsername, kw)
                                .or().like(UserEntity::getName, kw))
                        .eq(status != null && !status.isBlank(), UserEntity::getStatus, status)
                        .orderByDesc(UserEntity::getCreatedAt));
        List<Map<String, Object>> records = page.getRecords().stream().map(u -> {
            Map<String, Object> vo = new HashMap<>();
            vo.put("id", u.getId());
            vo.put("username", u.getUsername());
            vo.put("name", u.getName());
            vo.put("email", u.getEmail());
            vo.put("status", u.getStatus());
            vo.put("superUser", u.getIsSuper() != null && u.getIsSuper() == 1);
            vo.put("roleId", roleByUser.get(u.getId()));
            vo.put("createdAt", u.getCreatedAt());
            return vo;
        }).collect(Collectors.toList());
        return ApiResult.ok(PageVO.from(page, records));
    }

    @PostMapping
    @RequiresPermission(Permissions.SYS_USER_MANAGE)
    @Audit(action = "user.create", resourceType = "user")
    public ApiResult<Map<String, Object>> create(@RequestBody CreateUserRequest request) {
        validateUsername(request.getUsername());
        validatePassword(request.getPassword());
        if (userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, request.getUsername())) > 0) {
            throw new EmonLensException(ErrorCode.CONFLICT, "username already exists");
        }
        UserEntity user = new UserEntity();
        user.setUsername(request.getUsername());
        user.setPasswordHash(pwdService.hash(request.getPassword()));
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setStatus(UserEntity.STATUS_ACTIVE);
        user.setIsSuper(0);
        user.setDefaultWorkspaceId(CurrentContext.require().getWorkspaceId());
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        addMembership(user.getId(), request.getRoleId());
        return ApiResult.ok(Map.of("id", user.getId(), "username", user.getUsername()));
    }

    @PutMapping("/{id}/status")
    @RequiresPermission(Permissions.SYS_USER_MANAGE)
    @Audit(action = "user.status", resourceType = "user")
    public ApiResult<Void> changeStatus(@PathVariable String id, @RequestBody StatusRequest request) {
        requireMembership(id);
        UserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "user not found");
        }
        user.setStatus(request.getStatus());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return ApiResult.ok(null);
    }

    @PutMapping("/{id}/password")
    @RequiresPermission(Permissions.SYS_USER_MANAGE)
    @Audit(action = "user.reset-password", resourceType = "user")
    public ApiResult<Void> resetPassword(@PathVariable String id, @RequestBody PasswordRequest request) {
        validatePassword(request.getPassword());
        requireMembership(id);
        UserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "user not found");
        }
        user.setPasswordHash(pwdService.hash(request.getPassword()));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return ApiResult.ok(null);
    }

    @PutMapping("/{id}/role")
    @RequiresPermission(Permissions.SYS_USER_MANAGE)
    @Audit(action = "user.role", resourceType = "user")
    public ApiResult<Void> changeRole(@PathVariable String id, @RequestBody RoleRequest request) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        UserWorkspaceEntity membership = requireMembership(id);
        if (membership == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "membership not found");
        }
        membership.setRoleId(request.getRoleId());
        userWorkspaceMapper.updateById(membership);
        return ApiResult.ok(null);
    }

    private UserWorkspaceEntity requireMembership(String userId) {
        String workspaceId = CurrentContext.require().getWorkspaceId();
        UserWorkspaceEntity membership = userWorkspaceMapper.selectOne(Wrappers.<UserWorkspaceEntity>lambdaQuery()
                .eq(UserWorkspaceEntity::getUserId, userId)
                .eq(UserWorkspaceEntity::getWorkspaceId, workspaceId));
        if (membership == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "membership not found");
        }
        return membership;
    }

    private void addMembership(String userId, String roleId) {
        UserWorkspaceEntity membership = new UserWorkspaceEntity();
        membership.setUserId(userId);
        membership.setWorkspaceId(CurrentContext.require().getWorkspaceId());
        membership.setRoleId(roleId == null || roleId.isEmpty()
                ? "role-viewer" : roleId);
        membership.setCreatedAt(LocalDateTime.now());
        userWorkspaceMapper.insert(membership);
    }

    @Data
    public static class CreateUserRequest {
        private String username;
        private String password;
        private String name;
        private String email;
        private String roleId;
    }

    @Data
    public static class StatusRequest {
        private String status;
    }

    @Data
    public static class PasswordRequest {
        private String password;
    }

    @Data
    public static class RoleRequest {
        private String roleId;
    }
}
