CREATE TABLE operations_record (
    record_type VARCHAR(32) NOT NULL,
    record_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    business_status VARCHAR(64) NOT NULL,
    amount4 DECIMAL(38, 4),
    occurred_at VARCHAR(40) NOT NULL,
    summary VARCHAR(1000) NOT NULL,
    details_json TEXT NOT NULL,
    source_version BIGINT NOT NULL,
    projected_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (record_type, record_id),
    CONSTRAINT operations_record_type CHECK (
      record_type IN ('USER', 'RECHARGE', 'CONSUMPTION', 'IMAGE', 'VIDEO', 'PROMOTION')),
    CONSTRAINT operations_source_version CHECK (source_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE INDEX operations_record_user ON operations_record (user_id, occurred_at);
CREATE INDEX operations_record_filter ON operations_record (record_type, business_status, occurred_at);

CREATE TABLE operations_projection_event (
    event_id VARCHAR(128) NOT NULL PRIMARY KEY,
    record_type VARCHAR(32) NOT NULL,
    record_id VARCHAR(128) NOT NULL,
    semantics TEXT NOT NULL,
    projected_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
