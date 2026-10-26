CREATE TABLE publication_channel_limit (
    target VARCHAR(128) PRIMARY KEY,
    hour_start BIGINT NOT NULL,
    hour_count INT NOT NULL,
    day_start BIGINT NOT NULL,
    day_count INT NOT NULL,
    next_allowed_at TIMESTAMP(6) NOT NULL
);
