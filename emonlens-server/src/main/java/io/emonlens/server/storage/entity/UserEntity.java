package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_user")
public class UserEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_LOCKED = "LOCKED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String username;

    private String passwordHash;

    private String name;

    private String email;

    private String status;

    private Integer isSuper;

    private String defaultWorkspaceId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
