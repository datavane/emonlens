package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_event")
public class EventEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CLAIMED = "CLAIMED";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_DEAD = "DEAD";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String eventType;

    private String workspaceId;


    private String agentKey;

    private String payload;

    private String status;

    private String claimOwner;

    private LocalDateTime claimTime;

    private Integer retryCount;

    private LocalDateTime nextRetryAt;

    private LocalDateTime createdAt;
}
