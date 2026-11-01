package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 两个已完成 run 的成对比较；只证明相对偏好，不证明绝对合格。 */
@Data
@TableName("et_pairwise_evaluation")
public class PairwiseEvaluationEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String datasetId;
    private String runAId;
    private String runBId;
    private String protocol;
    private String protocolVersion;
    private Integer samplesPerDirection;
    private Boolean positionRandomized;
    private Long positionSeed;
    private Integer positionAFirstCount;
    private Integer positionBFirstCount;
    private String judgeModel;
    private String judgeModelsJson;
    private Integer judgeCount;
    private Integer itemCount;
    private Integer judgedCount;
    private Integer aWins;
    private Integer bWins;
    private Integer ties;
    private Integer unknownCount;
    private Double preferenceRate;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
}
