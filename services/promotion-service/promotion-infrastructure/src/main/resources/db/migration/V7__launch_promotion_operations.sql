CREATE TABLE promotion_reward_funding (
 funding_lot_id VARCHAR(128) PRIMARY KEY, order_id VARCHAR(128) NOT NULL UNIQUE,
 owner_id VARCHAR(128) NOT NULL, paid_amount4 DECIMAL(38,4) NOT NULL,
 original_points4 DECIMAL(38,4), beneficiary_id VARCHAR(128), point_rate6 DECIMAL(18,6) NOT NULL,
 binding_version BIGINT NOT NULL, policy_version BIGINT NOT NULL,
 created_at VARCHAR(40) NOT NULL, paid_at VARCHAR(40) NOT NULL, refunded BOOLEAN NOT NULL DEFAULT FALSE,
 net_successful_points4 DECIMAL(38,4) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE promotion_success_allocation (
 allocation_id VARCHAR(128) PRIMARY KEY, settlement_id VARCHAR(128) NOT NULL,
 funding_lot_id VARCHAR(128) NOT NULL, wallet_id VARCHAR(128) NOT NULL,
 successful_points4 DECIMAL(38,4) NOT NULL, reversed_points4 DECIMAL(38,4) NOT NULL DEFAULT 0,
 reward_points4 DECIMAL(38,4) NOT NULL DEFAULT 0, occurred_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX promotion_success_funding ON promotion_success_allocation(funding_lot_id);
CREATE TABLE promotion_consumption_receipt (
 event_id VARCHAR(128) PRIMARY KEY, facts TEXT NOT NULL, recorded_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE promotion_reward_delivery (
 sequence_no BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(128) NOT NULL UNIQUE,
 kind VARCHAR(16) NOT NULL, entitlement_id VARCHAR(128) NOT NULL, beneficiary_id VARCHAR(128) NOT NULL,
 settlement_id VARCHAR(128) NOT NULL, allocation_id VARCHAR(128) NOT NULL,
 points4 DECIMAL(38,4) NOT NULL, occurred_at VARCHAR(40) NOT NULL, delivered_at VARCHAR(40),
 wallet_id VARCHAR(128), credited_points4 DECIMAL(38,4), recovered_points4 DECIMAL(38,4), debt_points4 DECIMAL(38,4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE INDEX promotion_reward_pending ON promotion_reward_delivery(delivered_at,beneficiary_id,sequence_no);
CREATE TABLE promotion_admin_operation (
 command_id VARCHAR(128) PRIMARY KEY, kind VARCHAR(32) NOT NULL, subject_id VARCHAR(128) NOT NULL,
 admin_id VARCHAR(128) NOT NULL, facts TEXT NOT NULL, reason VARCHAR(2000), occurred_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE promotion_payout_month (
 withdrawal_id VARCHAR(128) PRIMARY KEY, promoter_user_id VARCHAR(128) NOT NULL,
 settlement_month VARCHAR(7), transfer_reference VARCHAR(256), paid_at VARCHAR(40),
 UNIQUE(promoter_user_id,settlement_month), UNIQUE(transfer_reference)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
ALTER TABLE promotion_withdrawal DROP CONSTRAINT promotion_withdrawal_amount;
ALTER TABLE promotion_withdrawal ADD CONSTRAINT promotion_withdrawal_amount CHECK(amount4 > 0);
DROP INDEX promotion_ledger_allocation ON promotion_commission_ledger;
CREATE INDEX promotion_ledger_allocation ON promotion_commission_ledger(allocation_id);

CREATE TABLE promotion_payout_attempt (
 command_id VARCHAR(128) PRIMARY KEY, withdrawal_id VARCHAR(128) NOT NULL,
 amount4 DECIMAL(38,4) NOT NULL, payout_account_id VARCHAR(128) NOT NULL,
 source_version BIGINT NOT NULL, account_name VARCHAR(256) NOT NULL,
 account_reference VARCHAR(512) NOT NULL, bank_name VARCHAR(256), qr_code_reference VARCHAR(512),
 recorded_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
