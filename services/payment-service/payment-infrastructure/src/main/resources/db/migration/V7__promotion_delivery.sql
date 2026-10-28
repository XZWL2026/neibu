CREATE TABLE payment_promotion_delivery (
    order_id VARCHAR(128) NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL,
    occurred_at VARCHAR(40) NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    next_attempt_at BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT payment_promotion_version CHECK (version IN (1,2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX payment_promotion_pending ON payment_promotion_delivery(completed,next_attempt_at,order_id);
