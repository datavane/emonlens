-- Pairwise 人工偏好校准；apply-once: 2026-10-20-pairwise-human-review
CREATE TABLE IF NOT EXISTS et_pairwise_human_review (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    evaluation_id VARCHAR(36) NOT NULL,
    item_id       VARCHAR(36) NOT NULL,
    reviewer      VARCHAR(64) NOT NULL,
    preference    VARCHAR(16) NOT NULL,
    note          VARCHAR(1000),
    created_at    DATETIME NOT NULL,
    UNIQUE KEY uk_pairwise_human_review (evaluation_id, item_id, reviewer),
    KEY idx_pairwise_human_review_eval (evaluation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
