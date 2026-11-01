-- LLM Judge 双人盲审 + 仲裁：评审槽位与 reviewer 唯一约束；apply-once: 2026-10-20-judge-double-blind
-- 历史记录视为 PRIMARY；旧单审唯一键升级为 (run,item,evaluator,version,reviewer_slot)。
DROP PROCEDURE IF EXISTS migrate_judge_double_blind;
DELIMITER $$
CREATE PROCEDURE migrate_judge_double_blind()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_judge_review'
          AND column_name = 'reviewer_slot'
    ) THEN
        ALTER TABLE et_judge_review
            ADD COLUMN reviewer_slot VARCHAR(16) NOT NULL DEFAULT 'PRIMARY' AFTER human_label;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'et_judge_review'
          AND index_name = 'uk_judge_review'
    ) THEN
        ALTER TABLE et_judge_review DROP INDEX uk_judge_review;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'et_judge_review'
          AND index_name = 'uk_judge_review_slot'
    ) THEN
        ALTER TABLE et_judge_review
            ADD UNIQUE KEY uk_judge_review_slot
                (run_id, item_id, evaluator_key, evaluator_version, reviewer_slot);
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'et_judge_review'
          AND index_name = 'uk_judge_review_reviewer'
    ) THEN
        ALTER TABLE et_judge_review
            ADD UNIQUE KEY uk_judge_review_reviewer
                (run_id, item_id, evaluator_key, evaluator_version, reviewed_by);
    END IF;
END$$
DELIMITER ;
CALL migrate_judge_double_blind();
DROP PROCEDURE migrate_judge_double_blind;
