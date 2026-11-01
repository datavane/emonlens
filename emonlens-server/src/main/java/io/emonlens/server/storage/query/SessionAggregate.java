package io.emonlens.server.storage.query;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.Data;

/** 会话聚合行（SQL GROUP BY 结果；列表分页不再把全量 trace 拉进内存）。 */
@Data
public class SessionAggregate {

    private String sessionId;
    private String name;
    private Long traceCount;
    private Long errorCount;
    private BigDecimal totalCostUsd;
    private Long totalDurationMs;
    private LocalDateTime firstSeen;
    private LocalDateTime lastActivity;

    /** 仅 distinct 关联查询使用 */
    private String userId;
    private String agentKey;
}
