CREATE TABLE ingestion_source_state (
    source_id VARCHAR(40) PRIMARY KEY,
    registered BOOLEAN NOT NULL,
    cursor_value VARCHAR(240),
    etag VARCHAR(512),
    last_modified VARCHAR(256),
    last_attempt_at BIGINT,
    last_success_at BIGINT,
    next_poll_at BIGINT NOT NULL,
    consecutive_failures INT NOT NULL,
    last_item_count INT NOT NULL,
    error_code VARCHAR(80),
    lease_owner VARCHAR(64),
    lease_until BIGINT NOT NULL,
    snapshot_payload MEDIUMTEXT,
    snapshot_etag VARCHAR(512),
    snapshot_modified VARCHAR(256),
    snapshot_offset INT NOT NULL,
    snapshot_created_at BIGINT
);

CREATE TABLE ingestion_inbox (
    id VARCHAR(64) PRIMARY KEY,
    source_id VARCHAR(40) NOT NULL,
    event_key VARCHAR(240) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    material_payload MEDIUMTEXT NOT NULL,
    state VARCHAR(40) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    lease_owner VARCHAR(64),
    lease_until BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    completed_at BIGINT,
    UNIQUE (source_id, event_key, fingerprint)
);

CREATE INDEX ingestion_inbox_due ON ingestion_inbox(state, next_attempt_at, lease_until);
