-- EmonLens StarRocks 数据面 DDL（span_event 插件化存储目标之一）
-- 对应实现：io.emonlens.server.storage.span.StarRocksSpanEventStore（MySQL 协议 JDBC）。
-- 启用步骤：
--   1) 本文件建表；
--   2) application.yml 配置：
--        emonlens.storage.mode: starrocks
--        emonlens.storage.starrocks.jdbc-url: jdbc:mysql://<fe>:9030/emonlens
--        emonlens.storage.starrocks.username / password
--   3) 启动即生效（注册器按 mode 装配，目标不可用会启动失败而非静默写错库）。
-- 说明：PRIMARY KEY 模型，主键 (workspace_id, trace_id, span_id) —— 重复 span 上报自动覆盖，
--       与关系库实现的"唯一键幂等跳过"语义对齐；写入路径无需 Stream Load 改造。
--       保留策略建议配合分区 TTL（partition_live_number）+ purgeBefore 兜底。

CREATE TABLE IF NOT EXISTS span_event (
    workspace_id    VARCHAR(64)  NOT NULL,
    trace_id        VARCHAR(64)  NOT NULL,
    span_id         VARCHAR(36)  NOT NULL,
    parent_span_id  VARCHAR(36),
    agent_key       VARCHAR(128) NOT NULL,
    agent_version   VARCHAR(128),
    span_type       VARCHAR(64),
    span_name       VARCHAR(512),
    start_time      DATETIME     NOT NULL,
    end_time        DATETIME,
    duration_ms     BIGINT,
    status          VARCHAR(32),
    error_type      VARCHAR(256),
    model_name      VARCHAR(256),
    tool_name       VARCHAR(256),
    input_tokens    BIGINT       DEFAULT 0,
    output_tokens   BIGINT       DEFAULT 0,
    cached_input_tokens BIGINT  DEFAULT 0,
    cost_usd        DECIMAL(18,8) DEFAULT 0,
    input_text      TEXT,
    output_text     TEXT,
    session_id      VARCHAR(128),
    environment     VARCHAR(64),
    user_id         VARCHAR(64),
    level           VARCHAR(16)  DEFAULT 'INFO',
    attributes      JSON,
    ingest_time     DATETIME     DEFAULT CURRENT_TIMESTAMP
)
ENGINE = OLAP
PRIMARY KEY (workspace_id, trace_id, span_id)
PARTITION BY date_trunc('day', start_time)
DISTRIBUTED BY HASH(workspace_id, trace_id)
ORDER BY (workspace_id, trace_id, span_id)
PROPERTIES (
  "replication_num" = "3",
  "partition_live_number" = "30"
);

-- trace_summary / eval_result / finding 等治理面表按设计保留在 MySQL（V1.1 §8.2），
-- span_event 为唯一切往 OLAP 的数据面表。
