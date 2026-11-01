-- Auto Rubrics 候选生成（只生成候选，不发布）；apply-once: 2026-10-20-auto-rubric-generation
DROP PROCEDURE IF EXISTS migrate_auto_rubric_generation;
DELIMITER $$
CREATE PROCEDURE migrate_auto_rubric_generation()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_auto_rubric_run'
          AND column_name = 'generation_calls'
    ) THEN
        ALTER TABLE et_auto_rubric_run
            ADD COLUMN generation_calls INT NOT NULL DEFAULT 0 AFTER judge_calls,
            ADD COLUMN generation_model VARCHAR(100) AFTER judge_model;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_auto_rubric_candidate'
          AND column_name = 'origin'
    ) THEN
        ALTER TABLE et_auto_rubric_candidate
            ADD COLUMN origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL' AFTER rule_text;
    END IF;
END$$
DELIMITER ;
CALL migrate_auto_rubric_generation();
DROP PROCEDURE migrate_auto_rubric_generation;
