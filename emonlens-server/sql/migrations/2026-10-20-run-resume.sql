-- 数据集运行断点续跑（Run/Attempt）；apply-once ledger: 2026-10-20-run-resume
DROP PROCEDURE IF EXISTS emonlens_add_run_resume;
DELIMITER $$
CREATE PROCEDURE emonlens_add_run_resume()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset_run'
          AND column_name = 'resumed_from_run_id'
    ) THEN
        ALTER TABLE et_dataset_run ADD COLUMN resumed_from_run_id VARCHAR(36) NULL AFTER dataset_revision_id;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset_run'
          AND column_name = 'attempt'
    ) THEN
        ALTER TABLE et_dataset_run ADD COLUMN attempt INT DEFAULT 1 AFTER resumed_from_run_id;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset_run'
          AND index_name = 'uk_dataset_run_resume'
    ) THEN
        ALTER TABLE et_dataset_run
            ADD UNIQUE KEY uk_dataset_run_resume (workspace_id, resumed_from_run_id, attempt);
    END IF;
END$$
DELIMITER ;
CALL emonlens_add_run_resume();
DROP PROCEDURE IF EXISTS emonlens_add_run_resume;
