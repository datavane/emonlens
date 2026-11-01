package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 外部业务报告版本；同一 experiment+report_version 唯一，payload_hash 用于识别同版本篡改。 */
@Data
@TableName("et_business_evidence")
public class BusinessEvidenceEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String experimentId;
    private String reportVersion;
    private String payloadHash;
    private String designJson;
    private String metricsJson;
    private String qualityChecksJson;
    private String comparisonsJson;
    private String adjustmentMethod;
    private String decision;
    private LocalDateTime reportedAt;
    private String receivedBy;
    private LocalDateTime receivedAt;
    private String sourceKeyId;
    private String signature;
    private String signedAt;
    private String nonce;
    private Boolean signatureVerified;
}
