CREATE TABLE promotion_income_draft (
  promoter_user_id VARCHAR(128) NOT NULL,
  settlement_month VARCHAR(7) NOT NULL,
  withdrawal_id VARCHAR(128) NOT NULL UNIQUE,
  adjustment_amount4 DECIMAL(38,4) NOT NULL DEFAULT 0,
  adjustment_version BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (promoter_user_id, settlement_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
