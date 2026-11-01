-- 数据集保鲜快照；apply-once: 2026-10-20-dataset-health-snapshot
CREATE TABLE IF NOT EXISTS et_dataset_health_snapshot (
    id                     VARCHAR(36) PRIMARY KEY,
    workspace_id           VARCHAR(64) NOT NULL,
    dataset_id             VARCHAR(36) NOT NULL,
    status                 VARCHAR(24) NOT NULL,
    item_count             INT         NOT NULL DEFAULT 0,
    requirement_coverage   DOUBLE,
    stale                  BOOLEAN     NOT NULL DEFAULT FALSE,
    stale_days             BIGINT      NOT NULL DEFAULT 0,
    latest_revision        INT,
    health_json            LONGTEXT,
    created_at             DATETIME     NOT NULL,
    KEY idx_health_snapshot_dataset (workspace_id, dataset_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
