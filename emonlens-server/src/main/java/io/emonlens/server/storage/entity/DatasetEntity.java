package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_dataset")
public class DatasetEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String datasetKey;
    private String description;
    /** 数据蓝图 JSON：最小样本量、风险/切分/用途最低配比、最大保鲜天数。 */
    private String blueprintJson;
    /** 绑定的领域包版本；null 表示未绑定。 */
    private String domainPackId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
