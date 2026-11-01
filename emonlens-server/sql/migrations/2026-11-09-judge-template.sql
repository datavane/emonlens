-- Judge 模板版本化 + claim 判分证据；apply-once ledger: 2026-11-09-judge-template
-- 幂等：按列独立判断；MySQL DDL 非事务，避免半途失败后重跑漏列。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-09-judge-template.sql

CREATE TABLE IF NOT EXISTS et_judge_template (
    id             VARCHAR(36) PRIMARY KEY,
    workspace_id   VARCHAR(64) NOT NULL,
    template_key   VARCHAR(100) NOT NULL,
    version        INT NOT NULL,
    name           VARCHAR(200),
    system_prompt  MEDIUMTEXT NOT NULL,
    user_prompt    MEDIUMTEXT,
    output_schema  MEDIUMTEXT,
    status         VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
    fingerprint    CHAR(64) NOT NULL,
    created_by     VARCHAR(64),
    created_at     DATETIME NOT NULL,
    UNIQUE KEY uk_judge_template_version (workspace_id, template_key, version),
    KEY idx_judge_template_key (workspace_id, template_key, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

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
        WHERE table_schema = DATABASE()
          AND table_name = tbl
          AND column_name = col
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', ddl);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL emonlens_add_column_if_missing('et_evaluator_version', 'template_key',
    'VARCHAR(100) NULL');
CALL emonlens_add_column_if_missing('et_evaluator_version', 'template_version',
    'INT NULL');
CALL emonlens_add_column_if_missing('et_evaluator_version', 'template_hash',
    'CHAR(64) NULL');

CALL emonlens_add_column_if_missing('et_eval_task', 'judge_template_key',
    'VARCHAR(100) NULL');
CALL emonlens_add_column_if_missing('et_eval_task', 'judge_template_version',
    'INT NULL');
CALL emonlens_add_column_if_missing('et_eval_task', 'judge_template_hash',
    'CHAR(64) NULL');
CALL emonlens_add_column_if_missing('et_eval_task', 'cache_key',
    'CHAR(64) NULL');

DROP PROCEDURE IF EXISTS emonlens_add_column_if_missing;
