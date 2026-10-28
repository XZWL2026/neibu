CREATE TABLE payment_account (
  user_id VARCHAR(128) PRIMARY KEY,
  locked_type VARCHAR(16),
  CONSTRAINT locked_account_type CHECK (locked_type IS NULL OR locked_type IN ('API','PERSONAL','ENTERPRISE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE payment_order (
  id VARCHAR(128) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  amount4 DECIMAL(38,4) NOT NULL,
  rate_snapshot4 DECIMAL(38,4) NOT NULL,
  recharge_type VARCHAR(16) NOT NULL,
  enterprise_name VARCHAR(200),
  created_at BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  transaction_id VARCHAR(128),
  paid_at VARCHAR(40),
  provider_order_id VARCHAR(128),
  checkout_url VARCHAR(2048),
  fulfilled BOOLEAN NOT NULL DEFAULT false,
  key_id VARCHAR(128),
  refund_reference VARCHAR(128),
  UNIQUE KEY provider_payment(transaction_id),
  INDEX reusable_order(user_id,status,expires_at),
  INDEX expiring_order(status,expires_at),
  CONSTRAINT payment_amount CHECK (amount4>0 AND rate_snapshot4>0),
  CONSTRAINT payment_status CHECK (status IN ('PENDING','EXPIRED','PAID','REFUND_PENDING','REFUNDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE payment_request (
  request_id VARCHAR(128) PRIMARY KEY,
  user_id VARCHAR(128) NOT NULL,
  order_id VARCHAR(128) NOT NULL,
  CONSTRAINT request_order FOREIGN KEY(order_id) REFERENCES payment_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
-- Transactional outbox: a verified transition and the work to fulfill/refund commit together.
CREATE TABLE payment_work (
  order_id VARCHAR(128) PRIMARY KEY,
  stage VARCHAR(16) NOT NULL,
  lease_token VARCHAR(40),
  lease_until BIGINT NOT NULL DEFAULT 0,
  next_attempt_at BIGINT NOT NULL DEFAULT 0,
  attempts INT NOT NULL DEFAULT 0,
  completed BOOLEAN NOT NULL DEFAULT false,
  last_error VARCHAR(64),
  INDEX pending_work(completed,next_attempt_at,lease_until),
  CONSTRAINT work_order FOREIGN KEY(order_id) REFERENCES payment_order(id),
  CONSTRAINT work_stage CHECK(stage IN ('FULFILL','REFUND'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
