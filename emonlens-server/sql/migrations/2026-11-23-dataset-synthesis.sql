-- 合成数据集 job/candidate + 样本溯源；apply-once ledger: 2026-11-23-dataset-synthesis
-- 幂等：表 IF NOT EXISTS；列按 information_schema 独立判断。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-23-dataset-synthesis.sql

DROP PROCEDURE IF EXISTS emonlens_add_column_if_missing;
DELIMITER $$
CREATE PROCEDURE emonlens_add_column_if_missing(
    IN tbl VARCHAR(64),
    IN col VARCHAR(64),
    IN ddl VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = tbl AND column_name = col
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL emonlens_add_column_if_missing('et_dataset_item', 'source_type',
    'VARCHAR(16) NOT NULL DEFAULT ''MANUAL''');
CALL emonlens_add_column_if_missing('et_dataset_item', 'source_ref',
    'VARCHAR(500) NULL');

DROP PROCEDURE IF EXISTS emonlens_add_column_if_missing;

CREATE TABLE IF NOT EXISTS et_dataset_synthesis_job (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    dataset_id        VARCHAR(36) NOT NULL,
    request_key       VARCHAR(128) NOT NULL,
    source_type       VARCHAR(24) NOT NULL,
    source_json       MEDIUMTEXT,
    config_json       MEDIUMTEXT NOT NULL,
    status            VARCHAR(24) NOT NULL,
    total_candidates  INT NOT NULL DEFAULT 0,
    accepted_count    INT NOT NULL DEFAULT 0,
    rejected_count    INT NOT NULL DEFAULT 0,
    model             VARCHAR(128),
    prompt_tokens     INT,
    completion_tokens INT,
    error             VARCHAR(1000),
    created_by        VARCHAR(64),
    created_at        DATETIME NOT NULL,
    started_at        DATETIME,
    finished_at       DATETIME,
    UNIQUE KEY uk_synthesis_request (workspace_id, request_key),
    KEY idx_synthesis_dataset (workspace_id, dataset_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_dataset_synthesis_candidate (
    id                VARCHAR(36) PRIMARY KEY,
    workspace_id      VARCHAR(64) NOT NULL,
    job_id            VARCHAR(36) NOT NULL,
    candidate_key     CHAR(64) NOT NULL,
    evolution         VARCHAR(32),
    payload_json      MEDIUMTEXT NOT NULL,
    source_ref        VARCHAR(500),
    quality_score     DOUBLE,
    filter_reason     VARCHAR(500),
    status            VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_item_id   VARCHAR(36),
    reviewed_by       VARCHAR(64),
    reviewed_at       DATETIME,
    created_at        DATETIME NOT NULL,
    UNIQUE KEY uk_synthesis_candidate (job_id, candidate_key),
    KEY idx_synthesis_candidate_status (job_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
