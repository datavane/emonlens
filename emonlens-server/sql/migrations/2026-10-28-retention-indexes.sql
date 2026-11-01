-- 时间窗列表 / 保留期扫描索引（存量库补齐）。
-- 背景：会话/用量/评分按 workspace_id + start_time 过滤；retention 按 created_at/computed_at 删除，
-- 缺索引时这些查询与清理会退化为全表扫描。
-- 幂等：逐索引检查 information_schema.statistics，已存在则跳过。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-28-retention-indexes.sql

SET @has := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_trace_summary' AND index_name = 'idx_trace_ws_start');
SET @sql := IF(@has = 0, 'CREATE INDEX idx_trace_ws_start ON et_trace_summary (workspace_id, start_time)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_span_event' AND index_name = 'idx_span_ws_start');
SET @sql := IF(@has = 0, 'CREATE INDEX idx_span_ws_start ON et_span_event (workspace_id, start_time)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_content' AND index_name = 'idx_content_created');
SET @sql := IF(@has = 0, 'CREATE INDEX idx_content_created ON et_content (created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_score' AND index_name = 'idx_score_computed');
SET @sql := IF(@has = 0, 'CREATE INDEX idx_score_computed ON et_score (computed_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_notify_record' AND index_name = 'idx_notify_created');
SET @sql := IF(@has = 0, 'CREATE INDEX idx_notify_created ON et_notify_record (created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_push_record' AND index_name = 'idx_push_created');
SET @sql := IF(@has = 0, 'CREATE INDEX idx_push_created ON et_push_record (created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_dataset_health_snapshot' AND index_name = 'idx_health_created');
SET @sql := IF(@has = 0, 'CREATE INDEX idx_health_created ON et_dataset_health_snapshot (created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_event_handler_delivery' AND index_name = 'idx_event_delivery_created');
SET @sql := IF(@has = 0, 'CREATE INDEX idx_event_delivery_created ON et_event_handler_delivery (created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
