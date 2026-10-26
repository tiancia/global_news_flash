CREATE TABLE live_editor_job (
    id VARCHAR(64) PRIMARY KEY,
    event_key VARCHAR(240) NOT NULL,
    event_version INT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    reservation_id VARCHAR(120),
    lease_until BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    last_error VARCHAR(128),
    UNIQUE (event_key, fingerprint)
);

CREATE TABLE automation_lease (
    id VARCHAR(64) PRIMARY KEY,
    owner VARCHAR(64),
    lease_until BIGINT NOT NULL
);
INSERT INTO automation_lease VALUES ('live-cycle', NULL, 0);

CREATE TABLE automation_run (
    id VARCHAR(64) PRIMARY KEY,
    started_at BIGINT NOT NULL,
    finished_at BIGINT,
    state VARCHAR(32) NOT NULL,
    processed INT NOT NULL,
    last_error VARCHAR(128)
);

CREATE TABLE automation_alert (
    id VARCHAR(64) PRIMARY KEY,
    category VARCHAR(64) NOT NULL,
    subject_id VARCHAR(240) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    created_at BIGINT NOT NULL
);
