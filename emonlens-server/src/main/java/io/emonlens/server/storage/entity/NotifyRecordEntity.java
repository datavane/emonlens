package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_notify_record")
public class NotifyRecordEntity {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DEAD = "DEAD";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;

    private String eventId;

    private String eventType;

    private String channel;

    private String senderId;

    private String target;

    private String subject;

    private String content;

    private String status;

    private String error;

    private Integer retryCount;

    private LocalDateTime createdAt;
}
