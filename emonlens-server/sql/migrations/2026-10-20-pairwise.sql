-- 成对偏好评估；apply-once: 2026-10-20-pairwise
CREATE TABLE IF NOT EXISTS et_pairwise_evaluation (
    id                    VARCHAR(36) PRIMARY KEY,
    workspace_id          VARCHAR(64) NOT NULL,
    dataset_id            VARCHAR(36) NOT NULL,
    run_a_id              VARCHAR(36) NOT NULL,
    run_b_id              VARCHAR(36) NOT NULL,
    protocol              VARCHAR(40) NOT NULL,
    samples_per_direction INT NOT NULL DEFAULT 1,
    judge_model           VARCHAR(100),
    item_count            INT NOT NULL DEFAULT 0,
    judged_count          INT NOT NULL DEFAULT 0,
    a_wins                INT NOT NULL DEFAULT 0,
    b_wins                INT NOT NULL DEFAULT 0,
    ties                  INT NOT NULL DEFAULT 0,
    unknown_count         INT NOT NULL DEFAULT 0,
    preference_rate       DOUBLE,
    status                VARCHAR(24) NOT NULL,
    created_by            VARCHAR(64),
    created_at            DATETIME     NOT NULL,
    KEY idx_pairwise_runs (workspace_id, run_a_id, run_b_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_pairwise_item (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    evaluation_id VARCHAR(36) NOT NULL,
    item_id       VARCHAR(36) NOT NULL,
    preference    VARCHAR(16) NOT NULL,
    reason        VARCHAR(1000),
    calls_json    TEXT,
    UNIQUE KEY uk_pairwise_item (evaluation_id, item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
