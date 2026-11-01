package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 合成数据集任务；候选先 DRAFT，人工批准后才进入 et_dataset_item。 */
@Data
@TableName("et_dataset_synthesis_job")
public class DatasetSynthesisJobEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String datasetId;
    private String requestKey;
    private String sourceType;
    private String sourceJson;
    private String configJson;
    private String status;
    private Integer totalCandidates;
    private Integer acceptedCount;
    private Integer rejectedCount;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private String error;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
