-- 内容采集幂等：同一 span 的同一 kind 只保留一行。
-- 背景：事件投递是 at-least-once，TRACE_COMPLETED 重试会重复执行 content capture，
-- 旧表没有唯一键 → et_content 重复膨胀（正文/指纹重复存储）。
-- 幂等：可重复执行；缺列/缺索引时补建；建唯一键前先清理历史重复行（保留最早一条）。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-28-content-unique.sql

SET @has_uk := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_content'
      AND index_name = 'uk_content_span_kind');
SET @sql := CASE
    WHEN @has_uk = 0
        THEN 'DELETE c1 FROM et_content c1 INNER JOIN et_content c2 ON c1.workspace_id = c2.workspace_id AND c1.trace_id = c2.trace_id AND c1.span_id = c2.span_id AND c1.kind = c2.kind AND c1.id > c2.id'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := CASE
    WHEN @has_uk = 0
        THEN 'ALTER TABLE et_content ADD UNIQUE KEY uk_content_span_kind (workspace_id, trace_id, span_id, kind)'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
