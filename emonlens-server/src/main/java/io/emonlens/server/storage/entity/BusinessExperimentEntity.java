package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 外部业务实验身份；同一 workspace+source+external id 唯一。 */
@Data
@TableName("et_business_experiment")
public class BusinessExperimentEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String source;
    private String externalExperimentId;
    private String agentKey;
    private String runId;
    private String candidateFingerprint;
    private String controlFingerprint;
    private String target;
    private String designType;
    private String designJson;
    private String primaryMetric;
    private String guardrailsJson;
    private Integer minSampleSize;
    private Boolean requireSignature;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
