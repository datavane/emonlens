-- LLM Judge 结果缓存；apply-once ledger: 2026-11-16-eval-cache
-- key 绑定 sample content + evaluator fingerprint + template hash + judge model + mode，
-- 任一语义字段变化即自然失效；TTL 由应用内清理任务删除。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-16-eval-cache.sql

CREATE TABLE IF NOT EXISTS et_eval_cache (
    id                    VARCHAR(36) PRIMARY KEY,
    workspace_id          VARCHAR(64) NOT NULL,
    cache_key             CHAR(64) NOT NULL,
    sample_hash           CHAR(64) NOT NULL,
    evaluator_fingerprint CHAR(64) NOT NULL,
    judge_model           VARCHAR(128),
    template_hash         CHAR(64),
    protocol_version      VARCHAR(40),
    label                 VARCHAR(24) NOT NULL,
    score                 DECIMAL(10,4),
    reason                VARCHAR(1024),
    result_json           MEDIUMTEXT,
    prompt_tokens         INT,
    completion_tokens     INT,
    created_at            DATETIME(6) NOT NULL,
    expires_at            DATETIME(6),
    UNIQUE KEY uk_eval_cache_key (workspace_id, cache_key),
    KEY idx_eval_cache_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
