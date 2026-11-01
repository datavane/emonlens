-- 数据集蓝图/保鲜：blueprint_json；apply-once: 2026-10-20-dataset-blueprint
DROP PROCEDURE IF EXISTS migrate_dataset_blueprint;
DELIMITER $$
CREATE PROCEDURE migrate_dataset_blueprint()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset'
          AND column_name = 'blueprint_json'
    ) THEN
        ALTER TABLE et_dataset ADD COLUMN blueprint_json TEXT NULL AFTER description;
    END IF;
END$$
DELIMITER ;
CALL migrate_dataset_blueprint();
DROP PROCEDURE migrate_dataset_blueprint;
