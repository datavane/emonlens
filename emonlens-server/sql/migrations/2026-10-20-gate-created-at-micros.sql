-- Gate 排序精度：由秒提升到微秒，避免同一秒内多条 Gate 全部被判 SUPERSEDED。
ALTER TABLE et_quality_gate
    MODIFY COLUMN created_at DATETIME(6) NOT NULL;
