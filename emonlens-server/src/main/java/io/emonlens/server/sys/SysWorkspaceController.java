package io.emonlens.server.sys;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.storage.entity.UserWorkspaceEntity;
import io.emonlens.server.storage.entity.WorkspaceEntity;
import io.emonlens.server.storage.mapper.UserWorkspaceMapper;
import io.emonlens.server.storage.mapper.WorkspaceMapper;

import lombok.RequiredArgsConstructor;

/**
 * 当前用户的工作空间列表（工作空间切换下拉）：数据自按当前用户过滤，登录即可读，无需权限注解。
 */
@RestController
@RequestMapping("/api/v1/sys/workspaces")
@RequiredArgsConstructor
public class SysWorkspaceController {

    private final UserWorkspaceMapper userWorkspaceMapper;
    private final WorkspaceMapper workspaceMapper;

    /** 当前用户的全部工作空间（工作空间切换下拉） */
    @GetMapping
    public ApiResult<List<WorkspaceEntity>> myWorkspaces() {
        List<String> wsIds = userWorkspaceMapper.selectList(Wrappers.<UserWorkspaceEntity>lambdaQuery()
                        .eq(UserWorkspaceEntity::getUserId, CurrentContext.require().getUserId()))
                .stream().map(m -> m.getWorkspaceId()).collect(Collectors.toList());
        return ApiResult.ok(wsIds.isEmpty() ? List.of()
                : workspaceMapper.selectList(Wrappers.<WorkspaceEntity>lambdaQuery()
                        .in(WorkspaceEntity::getId, wsIds)));
    }
}
