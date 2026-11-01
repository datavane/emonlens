package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_pairwise_human_review")
public class PairwiseHumanReviewEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String evaluationId;
    private String itemId;
    private String reviewer;
    private String preference;
    private String note;
    private LocalDateTime createdAt;
}
