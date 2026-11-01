-- et_score 修正：scope_id 需容纳 128 字符 agentKey；computed_at 提升到毫秒精度，
-- 避免同一秒内的并发重算撞 uk_score(workspace_id, scope_type, scope_id, time_window, computed_at)。
-- 幂等：可重复执行；列已达标时跳过。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-28-score-scope-precision.sql

SET @scope_len := (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'et_score' AND column_name = 'scope_id');
SET @sql := CASE
    WHEN @scope_len IS NOT NULL AND @scope_len < 128
        THEN 'ALTER TABLE et_score MODIFY COLUMN scope_id VARCHAR(128) NOT NULL'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @precision := (SELECT DATETIME_PRECISION FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'et_score' AND column_name = 'computed_at');
SET @sql := CASE
    WHEN @precision IS NOT NULL AND @precision < 3
        THEN 'ALTER TABLE et_score MODIFY COLUMN computed_at DATETIME(3) NOT NULL'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
