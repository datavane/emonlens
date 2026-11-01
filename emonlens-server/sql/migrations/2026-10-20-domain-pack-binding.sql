-- 数据集绑定领域包，并在 run 受理时冻结；apply-once: 2026-10-20-domain-pack-binding
DROP PROCEDURE IF EXISTS migrate_domain_pack_binding;
DELIMITER $$
CREATE PROCEDURE migrate_domain_pack_binding()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset'
          AND column_name = 'domain_pack_id'
    ) THEN
        ALTER TABLE et_dataset ADD COLUMN domain_pack_id VARCHAR(36) AFTER blueprint_json;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_dataset_run'
          AND column_name = 'domain_pack_id'
    ) THEN
        ALTER TABLE et_dataset_run ADD COLUMN domain_pack_id VARCHAR(36) AFTER dataset_revision_id;
    END IF;
END$$
DELIMITER ;
CALL migrate_domain_pack_binding();
DROP PROCEDURE migrate_domain_pack_binding;
