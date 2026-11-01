package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_trace_summary")
public class TraceSummaryEntity {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;


    private String agentKey;

    private String agentVersion;

    private String traceId;

    private String sessionId;
    /** 上报 span attributes 透传的 prompt 元数据（Langfuse prompt linkage） */
    private String promptName;
    private String promptVersion;
    private String environment;

    private String inputPreview;

    private String outputPreview;

    private java.math.BigDecimal totalCostUsd;

    private String modelName;

    private String userId;

    private String status;

    private Integer spanCount;

    private Integer errorCount;

    private String rootSpanId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private LocalDateTime lastSpanAt;

    private LocalDateTime completedAt;
}
