package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 证据签名 nonce；唯一约束防跨请求重放。 */
@Data
@TableName("et_business_evidence_nonce")
public class BusinessEvidenceNonceEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String sourceKeyId;
    private String nonce;
    private LocalDateTime createdAt;
}
