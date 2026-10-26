CREATE TABLE ops_notification (
    id VARCHAR(64) PRIMARY KEY,
    category VARCHAR(64) NOT NULL,
    subject_id VARCHAR(240) NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    attempts INT NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    last_error VARCHAR(128)
);
