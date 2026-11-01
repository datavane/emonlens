package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_auto_rubric_run")
public class AutoRubricRunEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private String datasetId;
    private String sourceRunId;
    private String status;
    private String auditSplit;
    private Integer auditCount;
    private Integer candidateCount;
    private Integer proposedCount;
    private Integer maxJudgeCalls;
    private Integer judgeCalls;
    private Integer generationCalls;
    private Integer bootstrapRounds;
    private Long bootstrapSeed;
    private Double minSelectionFrequency;
    private String generationModel;
    private String judgeModel;
    private String configJson;
    /** K 折贪心选择结果；未启用时 null。 */
    private String selectionJson;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
