package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 数据集保鲜/漂移的周期快照；用于趋势和审计，不参与 run 判定。 */
@Data
@TableName("et_dataset_health_snapshot")
public class DatasetHealthSnapshotEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String datasetId;
    private String status;
    private Integer itemCount;
    private Double requirementCoverage;
    private Boolean stale;
    private Long staleDays;
    private Integer latestRevision;
    private String healthJson;
    private LocalDateTime createdAt;
}
