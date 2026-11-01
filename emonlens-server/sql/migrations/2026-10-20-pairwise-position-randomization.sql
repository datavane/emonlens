-- Pairwise 位置随机化；apply-once: 2026-10-20-pairwise-position-randomization
DROP PROCEDURE IF EXISTS migrate_pairwise_position_randomization;
DELIMITER $$
CREATE PROCEDURE migrate_pairwise_position_randomization()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_pairwise_evaluation'
          AND column_name = 'position_randomized'
    ) THEN
        ALTER TABLE et_pairwise_evaluation
            ADD COLUMN position_randomized BOOLEAN NOT NULL DEFAULT FALSE AFTER samples_per_direction,
            ADD COLUMN position_seed BIGINT AFTER position_randomized,
            ADD COLUMN position_a_first_count INT NOT NULL DEFAULT 0 AFTER position_seed,
            ADD COLUMN position_b_first_count INT NOT NULL DEFAULT 0 AFTER position_a_first_count;
    END IF;
END$$
DELIMITER ;
CALL migrate_pairwise_position_randomization();
DROP PROCEDURE migrate_pairwise_position_randomization;
