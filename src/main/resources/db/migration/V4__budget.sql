CREATE TABLE budget_period (
    id VARCHAR(120) PRIMARY KEY,
    max_micros BIGINT NOT NULL,
    max_requests INT NOT NULL,
    reserved_micros BIGINT NOT NULL,
    spent_micros BIGINT NOT NULL,
    used_requests INT NOT NULL
);
CREATE TABLE budget_reservation (
    id VARCHAR(120) PRIMARY KEY,
    period_id VARCHAR(120) NOT NULL,
    amount_micros BIGINT NOT NULL,
    actual_micros BIGINT,
    state VARCHAR(24) NOT NULL,
    created_at BIGINT NOT NULL
);
