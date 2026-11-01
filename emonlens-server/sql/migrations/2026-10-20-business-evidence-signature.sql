-- 业务证据 RSA 公钥凭据、签名与 nonce；apply-once: 2026-10-20-business-evidence-signature
CREATE TABLE IF NOT EXISTS et_business_source_credential (
    id             VARCHAR(36) PRIMARY KEY,
    workspace_id   VARCHAR(64) NOT NULL,
    key_id         VARCHAR(64) NOT NULL,
    source         VARCHAR(100) NOT NULL,
    name           VARCHAR(200),
    public_key     TEXT NOT NULL,
    status         VARCHAR(16) NOT NULL,
    created_by     VARCHAR(64),
    created_at     DATETIME NOT NULL,
    revoked_at     DATETIME,
    UNIQUE KEY uk_business_source_key (workspace_id, key_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_business_evidence_nonce (
    id             VARCHAR(36) PRIMARY KEY,
    workspace_id   VARCHAR(64) NOT NULL,
    source_key_id  VARCHAR(64) NOT NULL,
    nonce          VARCHAR(128) NOT NULL,
    created_at     DATETIME NOT NULL,
    UNIQUE KEY uk_business_evidence_nonce (workspace_id, source_key_id, nonce)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

DROP PROCEDURE IF EXISTS migrate_business_evidence_signature;
DELIMITER $$
CREATE PROCEDURE migrate_business_evidence_signature()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_business_evidence'
          AND column_name = 'source_key_id'
    ) THEN
        ALTER TABLE et_business_evidence
            ADD COLUMN source_key_id VARCHAR(64) AFTER received_at,
            ADD COLUMN signature VARCHAR(1024) AFTER source_key_id,
            ADD COLUMN signed_at VARCHAR(64) AFTER signature,
            ADD COLUMN nonce VARCHAR(128) AFTER signed_at,
            ADD COLUMN signature_verified BOOLEAN AFTER nonce;
    END IF;
END$$
DELIMITER ;
CALL migrate_business_evidence_signature();
DROP PROCEDURE migrate_business_evidence_signature;

DROP PROCEDURE IF EXISTS migrate_business_experiment_require_signature;
DELIMITER $$
CREATE PROCEDURE migrate_business_experiment_require_signature()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'et_business_experiment'
          AND column_name = 'require_signature'
    ) THEN
        ALTER TABLE et_business_experiment
            ADD COLUMN require_signature BOOLEAN NOT NULL DEFAULT FALSE AFTER min_sample_size;
    END IF;
END$$
DELIMITER ;
CALL migrate_business_experiment_require_signature();
DROP PROCEDURE migrate_business_experiment_require_signature;
