-- 业务证据多重比较（Holm-Bonferroni）；apply-once: 2026-10-20-business-multiple-comparisons
DROP PROCEDURE IF EXISTS migrate_business_multiple_comparisons;
DELIMITER $$
CREATE PROCEDURE migrate_business_multiple_comparisons()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_business_evidence'
          AND column_name = 'comparisons_json'
    ) THEN
        ALTER TABLE et_business_evidence
            ADD COLUMN comparisons_json TEXT AFTER quality_checks_json,
            ADD COLUMN adjustment_method VARCHAR(40) AFTER comparisons_json;
    END IF;
END$$
DELIMITER ;
CALL migrate_business_multiple_comparisons();
DROP PROCEDURE migrate_business_multiple_comparisons;
