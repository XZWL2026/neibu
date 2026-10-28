CREATE TABLE billing_benefit_settlement (
 task_id VARCHAR(128) PRIMARY KEY, wallet_id VARCHAR(128) NOT NULL, facts TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE billing_benefit_allocation (
 allocation_id VARCHAR(40) PRIMARY KEY, settlement_id VARCHAR(128) NOT NULL,
 successful_points4 DECIMAL(38,4) NOT NULL, reversed_points4 DECIMAL(38,4) NOT NULL DEFAULT 0,
 payload TEXT NOT NULL, CHECK(reversed_points4 BETWEEN 0 AND successful_points4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE billing_benefit_delivery (
 event_id VARCHAR(128) PRIMARY KEY, payload TEXT NOT NULL, completed BOOLEAN NOT NULL DEFAULT FALSE,
 next_attempt_at BIGINT NOT NULL DEFAULT 0, INDEX benefit_due(completed,next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE billing_reward_account (
 beneficiary_user_id VARCHAR(128) PRIMARY KEY, wallet_id VARCHAR(128) NOT NULL,
 debt_points4 DECIMAL(38,4) NOT NULL DEFAULT 0, CHECK(debt_points4>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE billing_reward_entitlement (
 entitlement_id VARCHAR(128) PRIMARY KEY, beneficiary_user_id VARCHAR(128) NOT NULL,
 settlement_id VARCHAR(128) NOT NULL, allocation_id VARCHAR(128) NOT NULL,
 original_points4 DECIMAL(38,4) NOT NULL, reversed_points4 DECIMAL(38,4) NOT NULL DEFAULT 0,
 lot_id VARCHAR(256) NOT NULL, CHECK(reversed_points4 BETWEEN 0 AND original_points4),
 UNIQUE KEY reward_source(beneficiary_user_id,settlement_id,allocation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE billing_reward_event (
 event_id VARCHAR(128) PRIMARY KEY, facts TEXT NOT NULL, receipt TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE billing_benefit_refund (
 refund_id VARCHAR(128) NOT NULL, allocation_id VARCHAR(40) NOT NULL,
 points4 DECIMAL(38,4) NOT NULL, occurred_at VARCHAR(40) NOT NULL,
 PRIMARY KEY(refund_id,allocation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE billing_reward_refund_receipt (
 wallet_id VARCHAR(128) NOT NULL, refund_id VARCHAR(128) NOT NULL,
 requested_points4 DECIMAL(38,4) NOT NULL, credited_points4 DECIMAL(38,4) NOT NULL,
 PRIMARY KEY(wallet_id,refund_id), CHECK(credited_points4 BETWEEN 0 AND requested_points4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
