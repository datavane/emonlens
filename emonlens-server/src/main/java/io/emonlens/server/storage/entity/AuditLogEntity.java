package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_audit_log")
public class AuditLogEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String userId;

    private String username;

    private String workspaceId;

    private String action;

    private String resourceType;

    private String resourceId;

    private String detail;

    private String requestId;

    private String clientIp;

    private LocalDateTime createdAt;
}
