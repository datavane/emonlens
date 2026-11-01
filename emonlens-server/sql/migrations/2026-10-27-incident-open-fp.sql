-- Incident 唯一键：只约束“同一指纹最多一个 OPEN”，放开历史状态的重复。
-- 背景：旧键 uk_incident_fp_status(workspace_id, agent_key, fingerprint, status) 要求
-- 同一指纹每个状态全局唯一。告警 ACK/RESOLVE 后同指纹再次触发会新建 OPEN 告警，
-- 第二次 ACK（OPEN -> ACKNOWLEDGED）与上一条 ACKNOWLEDGED 记录撞唯一键，接口返回 500。
-- 方案：新增 open_fp，仅 OPEN 状态写入 fingerprint，其余状态为 NULL；唯一键改为
-- (workspace_id, agent_key, open_fp)。唯一索引允许多个 NULL，历史状态不再互相冲突。
-- 幂等：可重复执行；列/索引存在时跳过，OPEN 行始终回填 open_fp。
-- 执行：mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-27-incident-open-fp.sql

SET @has_col := (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'et_incident' AND column_name = 'open_fp');
SET @sql := CASE
    WHEN @has_col = 0
        THEN 'ALTER TABLE et_incident ADD COLUMN open_fp VARCHAR(64) NULL AFTER fingerprint'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 存量 OPEN 告警回填活动指纹；旧唯一键保证同一指纹最多一个 OPEN。
UPDATE et_incident
SET open_fp = fingerprint
WHERE status = 'OPEN' AND (open_fp IS NULL OR open_fp <> fingerprint);

SET @has_old := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_incident'
      AND index_name = 'uk_incident_fp_status');
SET @sql := CASE
    WHEN @has_old > 0
        THEN 'ALTER TABLE et_incident DROP INDEX uk_incident_fp_status'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @has_new := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'et_incident'
      AND index_name = 'uk_incident_open_fp');
SET @sql := CASE
    WHEN @has_new = 0
        THEN 'ALTER TABLE et_incident ADD UNIQUE KEY uk_incident_open_fp (workspace_id, agent_key, open_fp)'
    ELSE 'SELECT 1' END;
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
