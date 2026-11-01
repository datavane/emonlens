package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_user_workspace")
public class UserWorkspaceEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String workspaceId;

    private String roleId;

    private LocalDateTime createdAt;
}
