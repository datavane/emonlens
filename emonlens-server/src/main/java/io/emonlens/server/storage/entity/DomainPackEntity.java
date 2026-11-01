package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 不可变的领域包版本：质量模型、风险分类、数据蓝图和门禁模板的声明式集合。 */
@Data
@TableName("et_domain_pack")
public class DomainPackEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String packKey;
    private String version;
    private String schemaVersion;
    private String name;
    private String description;
    private String manifestJson;
    private String digest;
    private String status;
    private String compatibility;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime deprecatedAt;
}
