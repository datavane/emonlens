package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_finding")
public class FindingEntity {

    public static final String TYPE_MONITOR_VALIDATION = "MONITOR_VALIDATION";
    public static final String TYPE_MONITOR_TRAJECTORY = "MONITOR_TRAJECTORY";
    public static final String TYPE_BEHAVIOR_LOOP = "BEHAVIOR_LOOP";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;


    private String agentKey;

    private String agentVersion;

    private String traceId;

    private String spanId;

    private String dimension;

    private String type;

    private String severity;

    private String fingerprint;

    private String detectorId;

    private String message;

    private String evidence;

    private LocalDateTime createdAt;
}
