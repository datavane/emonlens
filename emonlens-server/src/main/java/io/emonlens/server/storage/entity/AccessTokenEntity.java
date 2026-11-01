package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_access_token")
public class AccessTokenEntity {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tokenHash;

    private String tokenMasked;

    private String name;

    private String workspaceId;

    private String scopes;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;
}
