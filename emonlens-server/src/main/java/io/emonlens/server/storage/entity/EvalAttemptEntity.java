package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

/**
 * 一次任务执行的外部调用/判定尝试。首切片按“一次任务执行 = 一条 attempt”记账，
 * 内部 HTTP 重试不拆行；后续需要按单次 provider call 审计时再细分。
 */
@Data
@TableName("et_eval_attempt")
public class EvalAttemptEntity {

    public static final String OUTCOME_SUCCEEDED = "SUCCEEDED";
    public static final String OUTCOME_UNKNOWN = "UNKNOWN";
    public static final String OUTCOME_FAILED = "FAILED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String runId;
    private String taskId;
    private Integer callSeq;
    private String outcome;
    private String verdict;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer latencyMs;
    private String reasonCode;
    @JsonIgnore
    private String detailJson;
    private LocalDateTime createdAt;
}
