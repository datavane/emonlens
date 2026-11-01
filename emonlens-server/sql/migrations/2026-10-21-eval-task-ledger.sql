-- V3.0 P1：Run/Task/Attempt 事实源首切片。
-- 幂等：可重复执行；不修改已有表，不回填历史 run。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-21-eval-task-ledger.sql

CREATE TABLE IF NOT EXISTS et_eval_task (
    id                    VARCHAR(36) PRIMARY KEY,
    workspace_id          VARCHAR(64) NOT NULL,
    run_id                VARCHAR(36) NOT NULL,
    run_item_id           VARCHAR(36) NOT NULL,
    dataset_id            VARCHAR(36) NOT NULL,
    item_id               VARCHAR(36) NOT NULL,
    item_order            INT NOT NULL DEFAULT 0,
    evaluator_id          VARCHAR(36) NOT NULL,
    evaluator_key         VARCHAR(128) NOT NULL,
    evaluator_version     INT NOT NULL,
    evaluator_order       INT NOT NULL DEFAULT 0,
    evaluator_fingerprint CHAR(64),
    execution_status      VARCHAR(16) NOT NULL,
    verdict               VARCHAR(24),
    score                 DECIMAL(8,4),
    reason_code           VARCHAR(64),
    reason                VARCHAR(1024),
    model                 VARCHAR(128),
    prompt_tokens         INT,
    completion_tokens     INT,
    total_tokens          INT,
    latency_ms            INT,
    attempt_count         INT NOT NULL DEFAULT 0,
    result_json           MEDIUMTEXT,
    evidence_hash         CHAR(64),
    created_at            DATETIME(6) NOT NULL,
    started_at            DATETIME(6),
    finished_at           DATETIME(6),
    CONSTRAINT uk_eval_task UNIQUE (workspace_id, run_id, run_item_id, evaluator_id),
    KEY idx_eval_task_run (workspace_id, run_id, execution_status),
    KEY idx_eval_task_run_item (run_item_id),
    KEY idx_eval_task_open (execution_status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_eval_attempt (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    run_id            VARCHAR(36) NOT NULL,
    task_id           VARCHAR(36) NOT NULL,
    call_seq          INT NOT NULL,
    outcome           VARCHAR(24) NOT NULL,
    verdict           VARCHAR(24),
    model             VARCHAR(128),
    prompt_tokens     INT,
    completion_tokens INT,
    total_tokens      INT,
    latency_ms        INT,
    reason_code       VARCHAR(64),
    detail_json       MEDIUMTEXT,
    created_at        DATETIME(6) NOT NULL,
    CONSTRAINT uk_eval_attempt UNIQUE (workspace_id, task_id, call_seq),
    KEY idx_eval_attempt_run (workspace_id, run_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
