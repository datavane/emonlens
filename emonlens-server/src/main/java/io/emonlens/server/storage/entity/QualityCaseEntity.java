package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 质量问题从发现、修复到回归验证的闭环。 */
@Data
@TableName("et_quality_case")
public class QualityCaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String datasetId;
    private String sourceRunId;
    private String sourceItemId;
    private String agentKey;
    private String title;
    private String phenomenon;
    private String evidenceJson;
    private String rootCauseCategory;
    private String owner;
    private String status;
    private String fixFingerprint;
    private String fixNote;
    private String regressionRunId;
    private String verifiedBy;
    private LocalDateTime verifiedAt;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
