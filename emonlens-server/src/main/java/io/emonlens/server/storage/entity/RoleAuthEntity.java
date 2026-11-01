package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_role_auth")
public class RoleAuthEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String roleId;

    private String permission;
}
