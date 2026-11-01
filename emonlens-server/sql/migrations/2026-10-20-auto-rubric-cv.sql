-- Auto Rubrics K 折贪心选择结果；apply-once: 2026-10-20-auto-rubric-cv
DROP PROCEDURE IF EXISTS migrate_auto_rubric_cv;
DELIMITER $$
CREATE PROCEDURE migrate_auto_rubric_cv()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_auto_rubric_run'
          AND column_name = 'selection_json'
    ) THEN
        ALTER TABLE et_auto_rubric_run
            ADD COLUMN selection_json TEXT AFTER config_json;
    END IF;
END$$
DELIMITER ;
CALL migrate_auto_rubric_cv();
DROP PROCEDURE migrate_auto_rubric_cv;
