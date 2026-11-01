-- 标注队列幂等：同一队列内同一 trace 只允许一条（并发“先查后插”会重复）。
-- 幂等：可重复执行；建唯一键前先清理历史重复行（保留最早一条）。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-28-annotation-item-unique.sql

SET @has_uk := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_annotation_queue_item'
      AND index_name = 'uk_annotation_queue_trace');
SET @sql := CASE
    WHEN @has_uk = 0
        THEN 'DELETE a FROM et_annotation_queue_item a INNER JOIN et_annotation_queue_item b ON a.queue_id = b.queue_id AND a.trace_id = b.trace_id AND a.id > b.id'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := CASE
    WHEN @has_uk = 0
        THEN 'ALTER TABLE et_annotation_queue_item ADD UNIQUE KEY uk_annotation_queue_trace (queue_id, trace_id)'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
