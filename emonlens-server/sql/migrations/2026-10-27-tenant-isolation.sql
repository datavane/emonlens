-- 多租户隔离：span / trace_summary 的唯一键必须包含 workspace_id。
-- 背景：旧键 uk_span(span_id, trace_id)、uk_trace(trace_id) 是全局唯一，两个工作区
-- 使用相同 trace_id 时，后写入者覆盖先写入者的 summary，重复 span 被当作幂等重复丢弃。
-- 幂等：可重复执行；仅在索引存在且未包含 workspace_id 时重建，缺失时补建。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-27-tenant-isolation.sql

SET @has_span := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_span_event' AND index_name = 'uk_span');
SET @span_ws := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_span_event' AND index_name = 'uk_span'
      AND column_name = 'workspace_id');
SET @sql := CASE
    WHEN @has_span = 1 AND @span_ws = 0
        THEN 'ALTER TABLE et_span_event DROP INDEX uk_span, ADD UNIQUE KEY uk_span (workspace_id, trace_id, span_id)'
    WHEN @has_span = 0
        THEN 'ALTER TABLE et_span_event ADD UNIQUE KEY uk_span (workspace_id, trace_id, span_id)'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_trace := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_trace_summary' AND index_name = 'uk_trace');
SET @trace_ws := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_trace_summary' AND index_name = 'uk_trace'
      AND column_name = 'workspace_id');
SET @sql := CASE
    WHEN @has_trace = 1 AND @trace_ws = 0
        THEN 'ALTER TABLE et_trace_summary DROP INDEX uk_trace, ADD UNIQUE KEY uk_trace (workspace_id, trace_id)'
    WHEN @has_trace = 0
        THEN 'ALTER TABLE et_trace_summary ADD UNIQUE KEY uk_trace (workspace_id, trace_id)'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
