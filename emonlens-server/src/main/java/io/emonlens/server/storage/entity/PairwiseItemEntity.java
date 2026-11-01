package io.emonlens.server.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 逐样本双向聚合后的偏好；calls_json 保留每次原始输出与映射，便于审计。 */
@Data
@TableName("et_pairwise_item")
public class PairwiseItemEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String evaluationId;
    private String itemId;
    private String preference;
    private String reason;
    private String callsJson;
}
