-- 数据集导入幂等查询索引；apply-once ledger: 2026-11-30-dataset-item-hash-index
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-30-dataset-item-hash-index.sql

SET @has_idx := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_dataset_item'
      AND index_name = 'idx_dataset_item_hash');
SET @sql := CASE
    WHEN @has_idx = 0
        THEN 'ALTER TABLE et_dataset_item ADD KEY idx_dataset_item_hash (dataset_id, content_hash)'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
