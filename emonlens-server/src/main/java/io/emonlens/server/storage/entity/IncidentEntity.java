package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_incident")
public class IncidentEntity {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String STATUS_RESOLVED = "RESOLVED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;


    private String agentKey;

    private String fingerprint;

    /** 仅 OPEN 状态写入 fingerprint，其余状态置空；唯一键 uk_incident_open_fp 借此保证同指纹只有一个活动告警。 */
    private String openFp;

    private String title;

    private String severity;

    private String status;

    private Integer findingCount;

    private LocalDateTime firstSeenAt;

    private LocalDateTime lastSeenAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
