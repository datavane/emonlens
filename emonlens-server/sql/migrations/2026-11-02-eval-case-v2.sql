-- 评测样本契约 v2：检索上下文 / 期望工具 / 多轮 / 样本级期望。
-- apply-once ledger: 2026-11-02-eval-case-v2
-- 幂等：按列独立判断；MySQL DDL 非事务，避免半途失败后重跑漏列。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-02-eval-case-v2.sql

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

-- et_dataset_item：工作集
CALL emonlens_add_column_if_missing('et_dataset_item', 'schema_version',
    'INT NOT NULL DEFAULT 1');
CALL emonlens_add_column_if_missing('et_dataset_item', 'retrieval_context_json',
    'MEDIUMTEXT NULL');
CALL emonlens_add_column_if_missing('et_dataset_item', 'expected_tools_json',
    'MEDIUMTEXT NULL');
CALL emonlens_add_column_if_missing('et_dataset_item', 'turns_json',
    'MEDIUMTEXT NULL');
CALL emonlens_add_column_if_missing('et_dataset_item', 'expectations_json',
    'MEDIUMTEXT NULL');
CALL emonlens_add_column_if_missing('et_dataset_item', 'content_hash',
    'CHAR(64) NULL');

-- et_dataset_revision_item：不可变修订
CALL emonlens_add_column_if_missing('et_dataset_revision_item', 'schema_version',
    'INT NOT NULL DEFAULT 1');
CALL emonlens_add_column_if_missing('et_dataset_revision_item', 'retrieval_context_json',
    'MEDIUMTEXT NULL');
CALL emonlens_add_column_if_missing('et_dataset_revision_item', 'expected_tools_json',
    'MEDIUMTEXT NULL');
CALL emonlens_add_column_if_missing('et_dataset_revision_item', 'turns_json',
    'MEDIUMTEXT NULL');
CALL emonlens_add_column_if_missing('et_dataset_revision_item', 'expectations_json',
    'MEDIUMTEXT NULL');

DROP PROCEDURE IF EXISTS emonlens_add_column_if_missing;
