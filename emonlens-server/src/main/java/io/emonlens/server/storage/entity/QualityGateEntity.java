package io.emonlens.server.storage.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

/** 一次准入判断：PASS / FAIL / BLOCKED，附可复核的检查明细。 */
@Data
@TableName("et_quality_gate")
public class QualityGateEntity {

    public static final String DECISION_PASS = "PASS";
    public static final String DECISION_FAIL = "FAIL";
    public static final String DECISION_BLOCKED = "BLOCKED";

    public static final String CHECK_PASS = "PASS";
    public static final String CHECK_FAIL = "FAIL";
    public static final String CHECK_BLOCKED = "BLOCKED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String runId;
    private String datasetId;
    private String policyKey;
    private String policyVersion;
    private String decision;
    private String summary;
    private String candidateLabel;
    private String candidateFingerprint;
    private String runStatus;
    private BigDecimal coverage;
    private Integer passCount;
    private Integer failCount;
    private Integer unknownCount;
    private Integer errorCount;
    @JsonIgnore
    private String checksJson;
    private LocalDateTime validUntil;
    private String createdBy;
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private java.util.List<java.util.Map<String, Object>> checks;
}
