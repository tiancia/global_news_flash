CREATE TABLE ingestion_article_failure (
    id VARCHAR(64) PRIMARY KEY,
    source_id VARCHAR(40) NOT NULL,
    snapshot_key VARCHAR(64) NOT NULL,
    item_key VARCHAR(64) NOT NULL,
    attempts INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE (source_id, snapshot_key, item_key)
);
CREATE INDEX ingestion_article_failure_recent ON ingestion_article_failure(updated_at);
