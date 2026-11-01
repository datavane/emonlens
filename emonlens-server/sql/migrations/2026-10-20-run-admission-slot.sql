-- 数据集运行数据库级准入槽；apply-once: 2026-10-20-run-admission-slot
-- 多实例受理时用单行主键唯一约束保证同一数据库只有一个 active run，避免 select-count 竞态。
CREATE TABLE IF NOT EXISTS et_dataset_run_slot (
    slot_key     VARCHAR(64)  PRIMARY KEY,
    run_id       VARCHAR(36)  NOT NULL,
    acquired_at  DATETIME     NOT NULL,
    expires_at   DATETIME     NOT NULL,
    UNIQUE KEY uk_dataset_run_slot_run (run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
