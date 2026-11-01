-- 不可变领域包版本；apply-once: 2026-10-20-domain-pack
CREATE TABLE IF NOT EXISTS et_domain_pack (
    id                 VARCHAR(36) PRIMARY KEY,
    workspace_id       VARCHAR(64) NOT NULL,
    pack_key           VARCHAR(64) NOT NULL,
    version            VARCHAR(32) NOT NULL,
    schema_version     VARCHAR(32) NOT NULL,
    name               VARCHAR(200) NOT NULL,
    description        VARCHAR(1000),
    manifest_json      LONGTEXT    NOT NULL,
    digest             VARCHAR(64) NOT NULL,
    status             VARCHAR(24) NOT NULL,
    compatibility      VARCHAR(64),
    created_by         VARCHAR(64),
    created_at         DATETIME    NOT NULL,
    deprecated_at      DATETIME,
    UNIQUE KEY uk_domain_pack_version (workspace_id, pack_key, version),
    KEY idx_domain_pack_key (workspace_id, pack_key, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
