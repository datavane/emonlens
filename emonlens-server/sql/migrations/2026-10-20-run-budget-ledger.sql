-- 外部调用预算账本；apply-once ledger: 2026-10-20-run-budget-ledger
DROP PROCEDURE IF EXISTS emonlens_add_run_budget_ledger;
DELIMITER $$
CREATE PROCEDURE emonlens_add_run_budget_ledger()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset_run'
          AND column_name = 'external_calls_reserved'
    ) THEN
        ALTER TABLE et_dataset_run
            ADD COLUMN external_calls_reserved INT NULL;
    END IF;
END$$
DELIMITER ;
CALL emonlens_add_run_budget_ledger();
DROP PROCEDURE IF EXISTS emonlens_add_run_budget_ledger;
