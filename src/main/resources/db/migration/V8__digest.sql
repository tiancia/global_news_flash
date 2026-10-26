CREATE TABLE news_digest (
    id VARCHAR(64) PRIMARY KEY,
    digest_date VARCHAR(10) NOT NULL,
    target VARCHAR(128) NOT NULL,
    event_key VARCHAR(255) NOT NULL,
    publication_id VARCHAR(36),
    body TEXT NOT NULL,
    operation_ids_payload TEXT NOT NULL,
    items_payload TEXT NOT NULL,
    window_start BIGINT NOT NULL,
    window_end BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT uq_digest_date_target UNIQUE (digest_date,target),
    CONSTRAINT uq_digest_event UNIQUE (event_key),
    CONSTRAINT uq_digest_publication UNIQUE (publication_id),
    CONSTRAINT fk_digest_publication FOREIGN KEY (publication_id) REFERENCES publication_operation(id)
);
