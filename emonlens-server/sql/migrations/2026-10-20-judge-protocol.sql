-- Judge 校准协议版本；apply-once: 2026-10-20-judge-protocol
DROP PROCEDURE IF EXISTS migrate_judge_protocol;
DELIMITER $$
CREATE PROCEDURE migrate_judge_protocol()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_judge_review'
          AND column_name = 'protocol_version'
    ) THEN
        ALTER TABLE et_judge_review
            ADD COLUMN protocol_version VARCHAR(40) NOT NULL DEFAULT 'judge-calibration/1'
            AFTER reviewer_slot;
    END IF;
END$$
DELIMITER ;
CALL migrate_judge_protocol();
DROP PROCEDURE migrate_judge_protocol;
