package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_monitor")
public class MonitorEntity {

    public static final String KIND_VALIDATION = "VALIDATION";
    public static final String KIND_TRAJECTORY = "TRAJECTORY";
    public static final String KIND_EVALUATION = "EVALUATION";
    public static final String KIND_METRIC = "METRIC";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;


    private String agentKey;

    private String monitorKey;

    private String kind;

    private String specJson;

    private String severity;

    private String dimension;

    private Integer enabled;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
