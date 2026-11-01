package io.emonlens.server.sys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.storage.entity.RoleAuthEntity;
import io.emonlens.server.storage.entity.RoleEntity;
import io.emonlens.server.storage.mapper.RoleAuthMapper;
import io.emonlens.server.storage.mapper.RoleMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/sys/roles")
@RequiredArgsConstructor
public class SysRoleController {

    private final RoleMapper roleMapper;
    private final RoleAuthMapper roleAuthMapper;
    private final io.emonlens.server.storage.mapper.UserWorkspaceMapper userWorkspaceMapper;

    @GetMapping
    @RequiresPermission(Permissions.SYS_ROLE_MANAGE)
    public ApiResult<List<Map<String, Object>>> list() {
        // 每角色成员绑定数（改权限/删除的影响面）
        Map<String, Long> memberCountByRole = userWorkspaceMapper.selectList(Wrappers.emptyWrapper())
                .stream().collect(Collectors.groupingBy(
                        io.emonlens.server.storage.entity.UserWorkspaceEntity::getRoleId, Collectors.counting()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (RoleEntity role : roleMapper.selectList(Wrappers.emptyWrapper())) {
            Map<String, Object> vo = new HashMap<>();
            vo.put("id", role.getId());
            vo.put("code", role.getCode());
            vo.put("name", role.getName());
            vo.put("builtin", role.getIsBuiltin() != null && role.getIsBuiltin() == 1);
            vo.put("memberCount", memberCountByRole.getOrDefault(role.getId(), 0L).intValue());
            vo.put("permissions", roleAuthMapper.selectList(Wrappers.<RoleAuthEntity>lambdaQuery()
                            .eq(RoleAuthEntity::getRoleId, role.getId())).stream()
                    .map(RoleAuthEntity::getPermission).sorted().collect(Collectors.toList()));
            result.add(vo);
        }
        return ApiResult.ok(result);
    }

    @PostMapping
    @RequiresPermission(Permissions.SYS_ROLE_MANAGE)
    @Audit(action = "role.create", resourceType = "role")
    public ApiResult<Map<String, Object>> create(@RequestBody CreateRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "角色名称不能为空");
        }
        String code = request.getName().trim().replaceAll("\\s+", "_").toUpperCase();
        if (code.length() > 64) {
            code = code.substring(0, 64);
        }
        // code 唯一性
        if (roleMapper.selectCount(Wrappers.<RoleEntity>lambdaQuery()
                .eq(RoleEntity::getCode, code)) > 0) {
            throw new EmonLensException(ErrorCode.CONFLICT, "角色 code 已存在: " + code);
        }
        RoleEntity entity = new RoleEntity();
        entity.setCode(code);
        entity.setName(request.getName().trim());
        entity.setIsBuiltin(0);
        roleMapper.insert(entity);
        Map<String, Object> vo = new HashMap<>();
        vo.put("id", entity.getId());
        vo.put("code", entity.getCode());
        vo.put("name", entity.getName());
        vo.put("builtin", false);
        vo.put("permissions", List.of());
        return ApiResult.ok(vo);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permissions.SYS_ROLE_MANAGE)
    @Audit(action = "role.delete", resourceType = "role")
    public ApiResult<Void> delete(@PathVariable String id) {
        RoleEntity role = roleMapper.selectById(id);
        if (role == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "role not found");
        }
        if (role.getIsBuiltin() != null && role.getIsBuiltin() == 1) {
            throw new EmonLensException(ErrorCode.CONFLICT, "内置角色不可删除");
        }
        roleMapper.deleteById(id);
        roleAuthMapper.delete(Wrappers.<RoleAuthEntity>lambdaQuery().eq(RoleAuthEntity::getRoleId, id));
        return ApiResult.ok(null);
    }

    @PutMapping("/{id}/permissions")
    @RequiresPermission(Permissions.SYS_ROLE_MANAGE)
    @Audit(action = "role.permissions", resourceType = "role")
    public ApiResult<Void> updatePermissions(@PathVariable String id, @RequestBody PermissionsRequest request) {
        RoleEntity role = roleMapper.selectById(id);
        if (role == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "role not found");
        }
        if (role.getIsBuiltin() != null && role.getIsBuiltin() == 1) {
            throw new EmonLensException(ErrorCode.CONFLICT, "内置角色权限不可修改");
        }
        roleAuthMapper.delete(Wrappers.<RoleAuthEntity>lambdaQuery().eq(RoleAuthEntity::getRoleId, id));
        if (request.getPermissions() != null) {
            for (String permission : request.getPermissions()) {
                RoleAuthEntity auth = new RoleAuthEntity();
                auth.setRoleId(id);
                auth.setPermission(permission);
                roleAuthMapper.insert(auth);
            }
        }
        return ApiResult.ok(null);
    }

    @Data
    public static class PermissionsRequest {
        private List<String> permissions;
    }

    @Data
    public static class CreateRequest {
        private String name;
        private String description;
    }
}
