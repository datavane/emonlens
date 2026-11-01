package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_auto_rubric_candidate")
public class AutoRubricCandidateEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String runId;
    private String ruleKey;
    private String ruleText;
    private String origin;
    private String decision;
    private Integer auditCount;
    private Integer violationCount;
    private Integer truePositive;
    private Integer falsePositive;
    private Integer falseNegative;
    private Integer trueNegative;
    private Double precisionRate;
    private Double recall;
    private Integer supportCount;
    private Double selectionFrequency;
    private Boolean stable;
    private Double violationRate;
    private Double minPrecision;
    private Integer minSupport;
    private String detailsJson;
    private String reviewNote;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
