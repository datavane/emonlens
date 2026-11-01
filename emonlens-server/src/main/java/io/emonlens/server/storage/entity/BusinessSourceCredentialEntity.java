package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 外部业务证据源的 RSA 公钥凭据；服务端不保存私钥。 */
@Data
@TableName("et_business_source_credential")
public class BusinessSourceCredentialEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String keyId;
    private String source;
    private String name;
    private String publicKey;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime revokedAt;
}
