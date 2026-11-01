-- =====================================================================
-- 用户试点最小重构（M1-M4）增量迁移
--
-- 适用：已经执行过旧版 sql/emonlens-mysql.sql 的 MySQL 库。
-- 新建库直接使用 sql/emonlens-mysql.sql（已包含以下列与唯一键）。
--
-- 执行前必须先做重复明细预检；若下面查询返回任何行，停止迁移并人工处理，
-- 不要删除历史记录。唯一键在存在重复时会失败，从而阻止迁移继续。
--
--   mysql -u<user> -p <db> < 2026-10-19-pilot-dataset-run.sql
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. 预检：同一 run 下重复 item。返回行即冲突，先人工处理再继续。
-- ---------------------------------------------------------------------
SELECT run_id, item_id, COUNT(*) AS duplicate_count
FROM et_dataset_run_item
GROUP BY run_id, item_id
HAVING COUNT(*) > 1;

-- ---------------------------------------------------------------------
-- 1. run 头：工作空间、幂等键、请求指纹、受理时冻结配置
-- ---------------------------------------------------------------------
ALTER TABLE et_dataset_run ADD COLUMN workspace_id VARCHAR(64);
ALTER TABLE et_dataset_run ADD COLUMN request_key VARCHAR(128);
ALTER TABLE et_dataset_run ADD COLUMN request_hash CHAR(64);
ALTER TABLE et_dataset_run ADD COLUMN config_snapshot_json MEDIUMTEXT;

-- 历史 run 的工作空间从数据集推导；推导不到时保持 NULL（仅历史数据，不参与新请求去重）
UPDATE et_dataset_run r
JOIN et_dataset d ON r.dataset_id = d.id
SET r.workspace_id = d.workspace_id
WHERE r.workspace_id IS NULL;

-- 新入口要求 request_key 非空；历史 run 的空键保留，唯一键中 NULL 不参与去重
ALTER TABLE et_dataset_run
    ADD CONSTRAINT uk_dataset_run_request UNIQUE (workspace_id, request_key);

-- ---------------------------------------------------------------------
-- 2. run 明细：受理时冻样本、执行结果明细
-- ---------------------------------------------------------------------
ALTER TABLE et_dataset_run_item ADD COLUMN sample_snapshot_json MEDIUMTEXT;
ALTER TABLE et_dataset_run_item ADD COLUMN result_details_json MEDIUMTEXT;

ALTER TABLE et_dataset_run_item
    ADD CONSTRAINT uk_dataset_run_item UNIQUE (run_id, item_id);
