package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_dataset_revision")
public class DatasetRevisionEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String datasetId;
    private Integer revision;
    private String label;
    private String manifestHash;
    private Integer itemCount;
    private String createdBy;
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private java.util.List<DatasetRevisionItemEntity> items;
}
