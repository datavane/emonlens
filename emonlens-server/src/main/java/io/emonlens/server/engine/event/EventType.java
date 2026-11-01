package io.emonlens.server.engine.event;

public enum EventType {

    /** trace 完成判定后触发检测流水 */
    TRACE_COMPLETED,
    /** 新 Finding 产出（FindingService.record 落库后） */
    FINDING_CREATED,
    /** 新建 OPEN Incident */
    INCIDENT_CREATED,
    /** Incident severity 升级 */
    INCIDENT_ESCALATED,
    /** Incident ack/resolve 状态迁移 */
    INCIDENT_STATUS_CHANGED,
    /** 一批评估结果产出（自动采样评估完成后） */
    EVAL_RESULT_READY,
    /** Trust Score 重算完成（ScoreRecomputeHandler 消费，Webhook 回推） */
    SCORE_CHANGED
}
