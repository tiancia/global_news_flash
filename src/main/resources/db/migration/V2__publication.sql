CREATE TABLE publication_control (
    id INT PRIMARY KEY,
    paused BOOLEAN NOT NULL
);
INSERT INTO publication_control (id, paused) VALUES (1, TRUE);

CREATE TABLE publication_control_audit (
    id VARCHAR(36) PRIMARY KEY,
    paused BOOLEAN NOT NULL,
    changed_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE publication_operation (
    id VARCHAR(36) PRIMARY KEY,
    event_key VARCHAR(255) NOT NULL,
    event_version INT NOT NULL,
    target VARCHAR(128) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    state VARCHAR(24) NOT NULL,
    body TEXT NOT NULL,
    original_operation_id VARCHAR(36),
    message_id VARCHAR(128),
    attempts INT NOT NULL,
    lease_token VARCHAR(36),
    lease_until TIMESTAMP(6),
    next_attempt_at TIMESTAMP(6),
    last_error VARCHAR(512),
    synthetic BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_publication_version UNIQUE (event_key, event_version, target, kind),
    CONSTRAINT fk_publication_original FOREIGN KEY (original_operation_id) REFERENCES publication_operation(id)
);
CREATE INDEX idx_publication_ready ON publication_operation (state, next_attempt_at);
CREATE INDEX idx_publication_lease ON publication_operation (state, lease_until);

CREATE TABLE publication_attempt (
    id VARCHAR(36) PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL,
    attempt_number INT NOT NULL,
    lease_token VARCHAR(36) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6),
    outcome VARCHAR(32) NOT NULL,
    error VARCHAR(512),
    CONSTRAINT fk_attempt_operation FOREIGN KEY (operation_id) REFERENCES publication_operation(id),
    CONSTRAINT uq_publication_attempt UNIQUE (operation_id, attempt_number)
);

CREATE TABLE publication_resolution (
    id VARCHAR(36) PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL,
    resolution VARCHAR(32) NOT NULL,
    message_id VARCHAR(128),
    note TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_resolution_operation FOREIGN KEY (operation_id) REFERENCES publication_operation(id),
    CONSTRAINT uq_operation_resolution UNIQUE (operation_id)
);

CREATE TABLE publication_alert (
    id VARCHAR(36) PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL,
    category VARCHAR(32) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    acknowledged_at TIMESTAMP(6),
    CONSTRAINT fk_alert_operation FOREIGN KEY (operation_id) REFERENCES publication_operation(id),
    CONSTRAINT uq_operation_alert UNIQUE (operation_id, category, reason_code)
);
CREATE INDEX idx_publication_alert_open ON publication_alert (acknowledged_at, created_at);
