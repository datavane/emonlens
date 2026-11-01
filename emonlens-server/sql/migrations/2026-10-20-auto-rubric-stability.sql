-- Auto Rubrics Bootstrap 稳定性选择；apply-once: 2026-10-20-auto-rubric-stability
DROP PROCEDURE IF EXISTS migrate_auto_rubric_stability;
DELIMITER $$
CREATE PROCEDURE migrate_auto_rubric_stability()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_auto_rubric_run'
          AND column_name = 'bootstrap_rounds'
    ) THEN
        ALTER TABLE et_auto_rubric_run
            ADD COLUMN bootstrap_rounds INT NOT NULL DEFAULT 200 AFTER generation_calls,
            ADD COLUMN bootstrap_seed BIGINT AFTER bootstrap_rounds,
            ADD COLUMN min_selection_frequency DOUBLE NOT NULL DEFAULT 0.6 AFTER bootstrap_seed;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_auto_rubric_candidate'
          AND column_name = 'selection_frequency'
    ) THEN
        ALTER TABLE et_auto_rubric_candidate
            ADD COLUMN selection_frequency DOUBLE AFTER support_count,
            ADD COLUMN stable BOOLEAN AFTER selection_frequency;
    END IF;
END$$
DELIMITER ;
CALL migrate_auto_rubric_stability();
DROP PROCEDURE migrate_auto_rubric_stability;
