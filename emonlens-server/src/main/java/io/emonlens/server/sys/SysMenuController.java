package io.emonlens.server.sys;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.server.storage.entity.MenuEntity;
import io.emonlens.server.storage.mapper.MenuMapper;

import lombok.RequiredArgsConstructor;

/**
 * 全量菜单元数据：登录即可读（无需权限注解）——前端按 permissions 过滤显示，
 * 加权限门禁会切断无 overview 权限角色的导航渲染；非敏感静态数据。
 */
@RestController
@RequestMapping("/api/v1/sys/menus")
@RequiredArgsConstructor
public class SysMenuController {

    private final MenuMapper menuMapper;

    /** 全量菜单（前端按 permissions 过滤显示；type: MENU|PAGE|BUTTON） */
    @GetMapping
    public ApiResult<List<MenuEntity>> list() {
        return ApiResult.ok(menuMapper.selectList(Wrappers.<MenuEntity>lambdaQuery()
                .orderByAsc(MenuEntity::getSort)));
    }
}
