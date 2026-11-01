-- 外部业务实验与版本化证据；apply-once: 2026-10-20-business-evidence
CREATE TABLE IF NOT EXISTS et_business_experiment (
    id                     VARCHAR(36) PRIMARY KEY,
    workspace_id           VARCHAR(64) NOT NULL,
    source                 VARCHAR(100) NOT NULL,
    external_experiment_id VARCHAR(200) NOT NULL,
    agent_key              VARCHAR(200),
    run_id                 VARCHAR(36),
    candidate_fingerprint  VARCHAR(128),
    control_fingerprint    VARCHAR(128),
    target                 VARCHAR(500),
    design_type            VARCHAR(24) NOT NULL,
    design_json            TEXT,
    primary_metric         VARCHAR(100) NOT NULL,
    guardrails_json        TEXT,
    min_sample_size        INT NOT NULL DEFAULT 0,
    started_at             DATETIME,
    ended_at               DATETIME,
    created_by             VARCHAR(64),
    created_at             DATETIME     NOT NULL,
    updated_at             DATETIME     NOT NULL,
    UNIQUE KEY uk_business_experiment_external (workspace_id, source, external_experiment_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS et_business_evidence (
    id                   VARCHAR(36) PRIMARY KEY,
    workspace_id         VARCHAR(64) NOT NULL,
    experiment_id        VARCHAR(36) NOT NULL,
    report_version       VARCHAR(64) NOT NULL,
    payload_hash         VARCHAR(64) NOT NULL,
    design_json          TEXT,
    metrics_json         TEXT,
    quality_checks_json  TEXT,
    decision             VARCHAR(24) NOT NULL,
    reported_at          DATETIME,
    received_by          VARCHAR(64),
    received_at          DATETIME     NOT NULL,
    UNIQUE KEY uk_business_evidence_version (experiment_id, report_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
