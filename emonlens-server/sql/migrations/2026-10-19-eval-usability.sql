-- =====================================================================
-- 评测可用性升级：可信评分 / 可复现运行 / 最小准入
--
-- 适用：已经执行过旧版 sql/emonlens-mysql.sql 的 MySQL 库。
-- 新建库直接使用 sql/emonlens-mysql.sql（已包含以下列与表）。
--
-- 本脚本是幂等增量迁移：
--   - 表已存在则跳过 CREATE TABLE；
--   - 列已存在则跳过 ADD COLUMN；
--   - 只放宽/新增列，不删除历史数据；
--   - 历史 V1 评分保留 score_version=1。
--
-- 用法：
--   mysql -h127.0.0.1 -uroot -p emonlens < sql/migrations/2026-10-19-eval-usability.sql
-- 建议执行前备份；迁移账号需要 ALTER/CREATE ROUTINE 权限（通常用 root 执行）。
-- =====================================================================

-- 幂等加列辅助过程：MySQL 8.0/8.4 没有通用的 ADD COLUMN IF NOT EXISTS。
DROP PROCEDURE IF EXISTS emonlens_add_column;
DROP PROCEDURE IF EXISTS emonlens_add_index;
DELIMITER $$
CREATE PROCEDURE emonlens_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- 幂等加索引辅助过程：迁移脚本可能被重复执行。
DROP PROCEDURE IF EXISTS emonlens_add_index;
DELIMITER $$
CREATE PROCEDURE emonlens_add_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_columns VARCHAR(255))
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index
    ) THEN
        SET @ddl = CONCAT('CREATE INDEX `', p_index, '` ON `', p_table, '` (', p_columns, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- 1. Trust Score：允许无证据时 score 为空，并记录证据覆盖率
ALTER TABLE et_score MODIFY COLUMN score DECIMAL(5,2) NULL;
CALL emonlens_add_column('et_score', 'score_version', 'INT NOT NULL DEFAULT 1');
CALL emonlens_add_column('et_score', 'evidence_status', 'VARCHAR(32)');
CALL emonlens_add_column('et_score', 'coverage', 'DECIMAL(5,2)');

-- 2. 评估器不可变版本
CREATE TABLE IF NOT EXISTS et_evaluator_version (
    id           VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    evaluator_id VARCHAR(36) NOT NULL,
    version      INT NOT NULL,
    type         VARCHAR(32) NOT NULL,
    spec_json    MEDIUMTEXT NOT NULL,
    fingerprint  CHAR(64) NOT NULL,
    created_at   DATETIME NOT NULL,
    UNIQUE KEY uk_evaluator_version (workspace_id, evaluator_id, version),
    KEY idx_evaluator_version (workspace_id, evaluator_id, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 3. 数据集样本标签：用途/风险/分组/划分/失败模式
CALL emonlens_add_column('et_dataset_item', 'purpose_tags', 'VARCHAR(255)');
CALL emonlens_add_column('et_dataset_item', 'risk_level', "VARCHAR(16) DEFAULT 'UNASSIGNED'");
CALL emonlens_add_column('et_dataset_item', 'group_key', 'VARCHAR(128)');
CALL emonlens_add_column('et_dataset_item', 'split', "VARCHAR(16) DEFAULT 'UNASSIGNED'");
CALL emonlens_add_column('et_dataset_item', 'failure_mode', 'VARCHAR(128)');

-- 4. 数据集修订
CREATE TABLE IF NOT EXISTS et_dataset_revision (
    id            VARCHAR(36) PRIMARY KEY,
    workspace_id  VARCHAR(64) NOT NULL,
    dataset_id    VARCHAR(36) NOT NULL,
    revision      INT NOT NULL,
    label         VARCHAR(128),
    manifest_hash CHAR(64) NOT NULL,
    item_count    INT NOT NULL DEFAULT 0,
    created_by    VARCHAR(64),
    created_at    DATETIME NOT NULL,
    UNIQUE KEY uk_dataset_revision (workspace_id, dataset_id, revision),
    KEY idx_dataset_revision (workspace_id, dataset_id, revision)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
CREATE TABLE IF NOT EXISTS et_dataset_revision_item (
    id              VARCHAR(36) PRIMARY KEY,
    workspace_id    VARCHAR(64) NOT NULL,
    revision_id     VARCHAR(36) NOT NULL,
    item_key        VARCHAR(64) NOT NULL,
    trace_id        VARCHAR(64),
    input           MEDIUMTEXT,
    expected_output MEDIUMTEXT,
    metadata_json   MEDIUMTEXT,
    purpose_tags    VARCHAR(255),
    risk_level      VARCHAR(16),
    group_key       VARCHAR(128),
    split           VARCHAR(16),
    failure_mode    VARCHAR(128),
    content_hash    CHAR(64) NOT NULL,
    item_order      INT NOT NULL,
    created_at      DATETIME NOT NULL,
    UNIQUE KEY uk_dataset_revision_item (revision_id, item_key),
    KEY idx_dataset_revision_item (revision_id, item_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 5. run 绑定 revision / 候选；逐评估器结果
CALL emonlens_add_column('et_dataset_run', 'dataset_revision_id', 'VARCHAR(36)');
CALL emonlens_add_column('et_dataset_run', 'candidate_label', 'VARCHAR(128)');
CALL emonlens_add_column('et_dataset_run', 'candidate_fingerprint', 'CHAR(64)');
CALL emonlens_add_column('et_dataset_run_item', 'evaluator_results_json', 'MEDIUMTEXT');
CALL emonlens_add_column('et_dataset_run_item', 'agent_key', 'VARCHAR(128)');
CALL emonlens_add_column('et_dataset_run_item', 'agent_version', 'VARCHAR(64)');
CALL emonlens_add_index('et_dataset_run_item', 'idx_dataset_run_item_agent', '`agent_key`');
CALL emonlens_add_index('et_dataset_run', 'idx_dataset_run_evidence', '`workspace_id`, `status`, `finished_at`');

-- 历史 run 保持 dataset_revision_id=NULL：读取兼容，但 Gate 必须返回 BLOCKED，
-- 不能把没有 revision/version 证据的历史结果当作新准入依据。

-- 6. 最小准入 Gate
CREATE TABLE IF NOT EXISTS et_quality_gate (
    id                    VARCHAR(36) PRIMARY KEY,
    workspace_id          VARCHAR(64) NOT NULL,
    run_id                VARCHAR(36) NOT NULL,
    dataset_id            VARCHAR(36) NOT NULL,
    policy_key            VARCHAR(64) NOT NULL,
    policy_version        VARCHAR(32) NOT NULL,
    decision              VARCHAR(16) NOT NULL,
    summary               VARCHAR(512),
    candidate_label       VARCHAR(128),
    candidate_fingerprint CHAR(64),
    run_status            VARCHAR(16),
    coverage              DECIMAL(5,2),
    pass_count            INT NOT NULL DEFAULT 0,
    fail_count            INT NOT NULL DEFAULT 0,
    unknown_count         INT NOT NULL DEFAULT 0,
    error_count           INT NOT NULL DEFAULT 0,
    checks_json           MEDIUMTEXT NOT NULL,
    valid_until           DATETIME,
    created_by            VARCHAR(64),
    created_at            DATETIME(6) NOT NULL,
    KEY idx_quality_gate_run (workspace_id, run_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

DROP PROCEDURE IF EXISTS emonlens_add_column;
DROP PROCEDURE IF EXISTS emonlens_add_index;
