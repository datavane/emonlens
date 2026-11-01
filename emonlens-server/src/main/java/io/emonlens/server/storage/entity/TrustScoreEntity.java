package io.emonlens.server.storage.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

@Data
@TableName("et_score")
public class TrustScoreEntity {

    public static final String SCOPE_AGENT = "AGENT";
    public static final String STATUS_TRUSTED = "TRUSTED";
    public static final String STATUS_AT_RISK = "AT_RISK";
    public static final String STATUS_CRITICAL = "CRITICAL";
    /** 证据不足：没有足够的有效判定，不能得出通过结论 */
    public static final String STATUS_INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE";

    public static final String EVIDENCE_COMPLETE = "COMPLETE";
    public static final String EVIDENCE_PARTIAL = "PARTIAL";
    public static final String EVIDENCE_NONE = "NONE";
    public static final String EVIDENCE_NO_DATA = "NO_DATA";
    public static final String EVIDENCE_NOT_CONFIGURED = "NOT_CONFIGURED";

    /** V1 为历史“无数据按满分”算法；V2 起无证据不产生分数 */
    public static final int SCORE_VERSION_LEGACY = 1;
    public static final int SCORE_VERSION_EVIDENCE = 2;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;

    private String scopeType;

    private String scopeId;

    private String timeWindow;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private BigDecimal score;

    private String status;

    private Integer scoreVersion;

    private String evidenceStatus;

    private BigDecimal coverage;

    private String dimensions;

    private String criticalGates;

    private LocalDateTime computedAt;
}
