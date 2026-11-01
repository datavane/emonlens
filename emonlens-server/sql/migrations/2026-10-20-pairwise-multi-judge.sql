-- Pairwise 多评审与协议版本；apply-once: 2026-10-20-pairwise-multi-judge
DROP PROCEDURE IF EXISTS migrate_pairwise_multi_judge;
DELIMITER $$
CREATE PROCEDURE migrate_pairwise_multi_judge()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_pairwise_evaluation'
          AND column_name = 'judge_models_json'
    ) THEN
        ALTER TABLE et_pairwise_evaluation
            ADD COLUMN protocol_version VARCHAR(40) AFTER protocol,
            ADD COLUMN judge_models_json TEXT AFTER judge_model,
            ADD COLUMN judge_count INT NOT NULL DEFAULT 1 AFTER judge_models_json;
    END IF;
END$$
DELIMITER ;
CALL migrate_pairwise_multi_judge();
DROP PROCEDURE migrate_pairwise_multi_judge;
