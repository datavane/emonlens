-- 数据集运行租约；apply-once ledger: 2026-10-20-run-lease
DROP PROCEDURE IF EXISTS emonlens_add_run_lease;
DELIMITER $$
CREATE PROCEDURE emonlens_add_run_lease()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset_run' AND column_name = 'lease_token'
    ) THEN
        ALTER TABLE et_dataset_run ADD COLUMN lease_token VARCHAR(64) NULL AFTER config_snapshot_json;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset_run' AND column_name = 'lease_expires_at'
    ) THEN
        ALTER TABLE et_dataset_run ADD COLUMN lease_expires_at DATETIME NULL AFTER lease_token;
    END IF;
END$$
DELIMITER ;
CALL emonlens_add_run_lease();
DROP PROCEDURE IF EXISTS emonlens_add_run_lease;
