ALTER TABLE promotion_payout_month ADD COLUMN base_amount4 DECIMAL(38,4);
ALTER TABLE promotion_payout_month ADD COLUMN adjustment_amount4 DECIMAL(38,4) NOT NULL DEFAULT 0;
ALTER TABLE promotion_payout_month ADD COLUMN adjustment_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE promotion_income_adjustment (
 command_id VARCHAR(128) PRIMARY KEY, withdrawal_id VARCHAR(128) NOT NULL,
 adjustment_version BIGINT NOT NULL, delta_amount4 DECIMAL(38,4) NOT NULL,
 reason VARCHAR(2000) NOT NULL, operator_id VARCHAR(128) NOT NULL,
 status VARCHAR(16) NOT NULL, occurred_at VARCHAR(40) NOT NULL,
 UNIQUE(withdrawal_id,adjustment_version),
 CONSTRAINT promotion_income_adjustment_status CHECK(status IN ('PENDING','SUPERSEDED','APPROVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
