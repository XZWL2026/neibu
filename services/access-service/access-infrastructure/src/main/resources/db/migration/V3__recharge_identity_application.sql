CREATE TABLE access_recharge_application (
 order_id VARCHAR(128) PRIMARY KEY,
 owner_id VARCHAR(128) NOT NULL,
 account_type VARCHAR(16) NOT NULL,
 paid_at DECIMAL(29,9) NULL,
 INDEX ix_recharge_owner_paid (owner_id,paid_at,order_id),
 recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
