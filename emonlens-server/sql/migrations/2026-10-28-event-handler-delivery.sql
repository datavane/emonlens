-- fan-out 事件的 handler 级幂等账本：事件重试时跳过已成功的 handler，
-- 避免重复触发 webhook / 重复重算评分快照。
-- 幂等：可重复执行。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-28-event-handler-delivery.sql

CREATE TABLE IF NOT EXISTS et_event_handler_delivery (
    id          VARCHAR(36)  PRIMARY KEY,
    event_id    VARCHAR(36)  NOT NULL,
    handler_key VARCHAR(191) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'DONE',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_handler (event_id, handler_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
