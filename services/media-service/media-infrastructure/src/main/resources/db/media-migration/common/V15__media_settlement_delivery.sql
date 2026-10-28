CREATE TABLE media_settlement_delivery (
 task_id VARCHAR(128) PRIMARY KEY,
 payload TEXT NOT NULL,
 completed BOOLEAN NOT NULL DEFAULT FALSE,
 next_attempt_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX media_settlement_due ON media_settlement_delivery(completed,next_attempt_at);
