package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 人工对 LLM Judge 判定的盲审记录；源 run 不可变，本表只追加/更新人工结论。 */
@Data
@TableName("et_judge_review")
public class JudgeReviewEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String runId;
    private String itemId;
    private String evaluatorKey;
    private Integer evaluatorVersion;
    /** 评审时的 judge 标签快照，便于审计。 */
    private String judgeLabel;
    private String humanLabel;
    /** PRIMARY / SECOND / ADJUDICATOR；历史单审记录为 PRIMARY。 */
    private String reviewerSlot;
    /** 校准协议版本；用于识别不同协议下的标签是否可混算。 */
    private String protocolVersion;
    private String note;
    private String reviewedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
