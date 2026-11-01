-- 不可变 Eval Suite 资产；apply-once ledger: 2026-10-20-eval-suite
CREATE TABLE IF NOT EXISTS et_eval_suite (
    id           VARCHAR(36) PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    suite_key    VARCHAR(100) NOT NULL,
    name         VARCHAR(200),
    description  VARCHAR(500),
    version      INT NOT NULL,
    spec_json    MEDIUMTEXT NOT NULL,
    fingerprint  CHAR(64) NOT NULL,
    created_at   DATETIME NOT NULL,
    UNIQUE KEY uk_eval_suite_version (workspace_id, suite_key, version),
    KEY idx_eval_suite_key (workspace_id, suite_key, version),
    KEY idx_eval_suite_fingerprint (workspace_id, suite_key, fingerprint)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

DROP PROCEDURE IF EXISTS emonlens_add_suite_column;
DELIMITER $$
CREATE PROCEDURE emonlens_add_suite_column()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset_run' AND column_name = 'suite_id'
    ) THEN
        ALTER TABLE et_dataset_run ADD COLUMN suite_id VARCHAR(36) NULL AFTER dataset_revision_id;
    END IF;
END$$
DELIMITER ;
CALL emonlens_add_suite_column();
DROP PROCEDURE IF EXISTS emonlens_add_suite_column;
