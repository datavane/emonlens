-- LLM Judge criterion 级结果；apply-once ledger: 2026-10-20-criterion-results
DROP PROCEDURE IF EXISTS emonlens_add_criterion_column;
DELIMITER $$
CREATE PROCEDURE emonlens_add_criterion_column()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_eval_result'
          AND column_name = 'criterion_results_json'
    ) THEN
        ALTER TABLE et_eval_result ADD COLUMN criterion_results_json MEDIUMTEXT NULL AFTER evidence;
    END IF;
END$$
DELIMITER ;
CALL emonlens_add_criterion_column();
DROP PROCEDURE IF EXISTS emonlens_add_criterion_column;
