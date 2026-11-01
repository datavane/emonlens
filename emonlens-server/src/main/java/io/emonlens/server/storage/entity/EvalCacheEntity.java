package io.emonlens.server.storage.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** LLM Judge 结果缓存；key 已包含所有影响判定的语义字段。 */
@Data
@TableName("et_eval_cache")
public class EvalCacheEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String cacheKey;
    private String sampleHash;
    private String evaluatorFingerprint;
    private String judgeModel;
    private String templateHash;
    private String protocolVersion;
    private String label;
    private BigDecimal score;
    private String reason;
    private String resultJson;
    private Integer promptTokens;
    private Integer completionTokens;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
