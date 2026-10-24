CREATE TABLE growth_target (
    target_id VARCHAR(128) PRIMARY KEY,
    label VARCHAR(256) NOT NULL,
    synthetic BOOLEAN NOT NULL,
    authorized BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE growth_distribution (
    operation_key VARCHAR(128) PRIMARY KEY,
    target_id VARCHAR(128) NOT NULL,
    body TEXT NOT NULL,
    body_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    synthetic BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (target_id) REFERENCES growth_target(target_id)
);

CREATE TABLE growth_invite (
    invite_key VARCHAR(128) PRIMARY KEY,
    target_id VARCHAR(128) NOT NULL,
    synthetic BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (target_id) REFERENCES growth_target(target_id)
);

CREATE TABLE growth_join_event (
    update_id BIGINT PRIMARY KEY,
    invite_key VARCHAR(512),
    target_id VARCHAR(128),
    attribution VARCHAR(32) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    synthetic BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE INDEX idx_growth_join_attribution ON growth_join_event(attribution);
