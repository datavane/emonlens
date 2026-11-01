-- 数据集样本软失效（不物理删除）；apply-once: 2026-10-20-dataset-item-quarantine
DROP PROCEDURE IF EXISTS migrate_dataset_item_quarantine;
DELIMITER $$
CREATE PROCEDURE migrate_dataset_item_quarantine()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset_item'
          AND column_name = 'status'
    ) THEN
        ALTER TABLE et_dataset_item
            ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' AFTER split,
            ADD COLUMN invalid_reason VARCHAR(500) AFTER status,
            ADD COLUMN invalidated_at DATETIME AFTER invalid_reason;
    END IF;
END$$
DELIMITER ;
CALL migrate_dataset_item_quarantine();
DROP PROCEDURE migrate_dataset_item_quarantine;
