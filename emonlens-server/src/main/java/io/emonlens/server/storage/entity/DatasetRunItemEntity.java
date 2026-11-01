package io.emonlens.server.storage.entity;

import java.util.Map;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

@Data
@TableName("et_dataset_run_item")
public class DatasetRunItemEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String runId;
    private String itemId;
    private String traceId;
    /** 冻结时持久化的 agent 归属；REPLAY 固定 dataset-replay，避免只在内存里存在。 */
    private String agentKey;
    private String agentVersion;
    /** REPLAY：样本原始关联 trace（与 traceId=重放生成的新 trace 区分） */
    private String sourceTraceId;
    /** REPLAY：LLM 重新生成的输出 */
    private String replayOutput;
    private java.math.BigDecimal score;
    private String label;
    private String reason;
    /** 受理时冻结的必要输入、参考、候选与检查上下文 */
    @JsonIgnore
    private String sampleSnapshotJson;
    /** 实际候选、原因码、usage 与执行明细 */
    @JsonIgnore
    private String resultDetailsJson;
    /** 多评估器逐项结果（数组），聚合 label/score 仍写在上面 */
    @JsonIgnore
    private String evaluatorResultsJson;

    @TableField(exist = false)
    private Map<String, Object> sampleSnapshot;
    @TableField(exist = false)
    private Map<String, Object> resultDetails;
    @TableField(exist = false)
    private java.util.List<Map<String, Object>> evaluatorResults;

    /** 证据查询联表带出的 run 完成时间，仅内存使用。 */
    @TableField(exist = false)
    private java.time.LocalDateTime runFinishedAt;
}
