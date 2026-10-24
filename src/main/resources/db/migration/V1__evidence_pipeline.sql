CREATE TABLE news_source (
    id VARCHAR(80) PRIMARY KEY,
    source_id VARCHAR(40) NOT NULL,
    enabled BOOLEAN NOT NULL,
    approved BOOLEAN NOT NULL,
    synthetic BOOLEAN NOT NULL,
    license_version VARCHAR(120) NOT NULL,
    permitted_producers VARCHAR(1000) NOT NULL,
    scope_description VARCHAR(1000) NOT NULL,
    UNIQUE (source_id, synthetic)
);

INSERT INTO news_source VALUES
('usgs:real', 'usgs', FALSE, FALSE, FALSE, 'pending', '', 'Unapproved real source; requires material-specific admission'),
('fed:real', 'fed', FALSE, FALSE, FALSE, 'pending', '', 'Unapproved real source; RSS alone may be insufficient'),
('gdelt:real', 'gdelt', FALSE, FALSE, FALSE, 'pending', '', 'Unapproved discovery only; no original media permission'),
('usgs:synthetic', 'usgs', TRUE, TRUE, TRUE, 'synthetic-v1', 'us', 'Locally authored fictional fixtures only'),
('fed:synthetic', 'fed', TRUE, TRUE, TRUE, 'synthetic-v1', 'federal-reserve-board', 'Locally authored fictional fixtures only'),
('gdelt:synthetic', 'gdelt', TRUE, TRUE, TRUE, 'synthetic-v1', 'synthetic.example.org', 'Locally authored fictional discovery fixtures only');

CREATE TABLE news_material (
    id VARCHAR(64) PRIMARY KEY,
    source_id VARCHAR(40) NOT NULL,
    event_key VARCHAR(240) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    discovered_at BIGINT NOT NULL,
    synthetic BOOLEAN NOT NULL,
    UNIQUE (source_id, event_key, fingerprint)
);

CREATE TABLE news_event (
    event_key VARCHAR(240) PRIMARY KEY,
    latest_version INT NOT NULL
);

CREATE TABLE news_event_version (
    id VARCHAR(64) PRIMARY KEY,
    event_key VARCHAR(240) NOT NULL,
    event_version INT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    editorial_state VARCHAR(30) NOT NULL,
    decision_payload TEXT NOT NULL,
    policy_version VARCHAR(100) NOT NULL,
    publication_id VARCHAR(64),
    materials_payload TEXT NOT NULL,
    policy_payload TEXT NOT NULL,
    sources_payload TEXT NOT NULL,
    budget_reservation_id VARCHAR(120),
    created_at BIGINT NOT NULL,
    UNIQUE (event_key, event_version),
    UNIQUE (event_key, fingerprint)
);

CREATE TABLE news_audit (
    id VARCHAR(64) PRIMARY KEY,
    action VARCHAR(80) NOT NULL,
    subject_id VARCHAR(240) NOT NULL,
    details VARCHAR(2000) NOT NULL,
    created_at BIGINT NOT NULL
);
