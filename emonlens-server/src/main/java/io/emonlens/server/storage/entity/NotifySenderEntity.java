package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_notify_sender")
public class NotifySenderEntity {

    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_WEBHOOK = "WEBHOOK";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;

    private String channel;

    private String name;

    private String config;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
