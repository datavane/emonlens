-- LLM Judge 人工盲审校准记录（可重复执行）。
CREATE TABLE IF NOT EXISTS et_judge_review (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    run_id            VARCHAR(36) NOT NULL,
    item_id           VARCHAR(36) NOT NULL,
    evaluator_key     VARCHAR(100) NOT NULL,
    evaluator_version INT NOT NULL,
    judge_label       VARCHAR(16) NOT NULL,
    human_label       VARCHAR(16) NOT NULL,
    note              VARCHAR(500),
    reviewed_by       VARCHAR(64),
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    UNIQUE KEY uk_judge_review (run_id, item_id, evaluator_key, evaluator_version),
    KEY idx_judge_review_run (workspace_id, run_id, evaluator_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
