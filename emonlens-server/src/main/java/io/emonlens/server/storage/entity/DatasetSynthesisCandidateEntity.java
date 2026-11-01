package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 合成候选；payload_json 与样本 v2 字段同构，批准后复制为工作集样本。 */
@Data
@TableName("et_dataset_synthesis_candidate")
public class DatasetSynthesisCandidateEntity {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String jobId;
    private String candidateKey;
    private String evolution;
    private String payloadJson;
    private String sourceRef;
    private Double qualityScore;
    private String filterReason;
    private String status;
    private String createdItemId;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
