-- run 级 token 预算账本；apply-once: 2026-10-20-run-token-budget
DROP PROCEDURE IF EXISTS emonlens_add_run_token_budget;
DELIMITER $$
CREATE PROCEDURE emonlens_add_run_token_budget()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset_run' AND column_name = 'max_tokens') THEN
        ALTER TABLE et_dataset_run ADD COLUMN max_tokens BIGINT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset_run' AND column_name = 'tokens_reserved') THEN
        ALTER TABLE et_dataset_run ADD COLUMN tokens_reserved BIGINT NOT NULL DEFAULT 0;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset_run' AND column_name = 'tokens_spent') THEN
        ALTER TABLE et_dataset_run ADD COLUMN tokens_spent BIGINT NOT NULL DEFAULT 0;
    END IF;
END$$
DELIMITER ;
CALL emonlens_add_run_token_budget();
DROP PROCEDURE IF EXISTS emonlens_add_run_token_budget;
