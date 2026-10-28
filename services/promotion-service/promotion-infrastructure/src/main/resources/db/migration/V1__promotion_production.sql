CREATE TABLE promotion_applicant_eligibility (
    user_id VARCHAR(128) NOT NULL PRIMARY KEY,
    account_type VARCHAR(32) NOT NULL,
    account_status VARCHAR(32) NOT NULL,
    registered_at VARCHAR(40) NOT NULL,
    source_version BIGINT NOT NULL,
    last_source_event_id VARCHAR(128) NOT NULL UNIQUE,
    updated_at VARCHAR(40) NOT NULL,
    CONSTRAINT promotion_eligibility_status CHECK (account_status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT promotion_eligibility_version CHECK (source_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE promotion_application (
    application_id VARCHAR(128) NOT NULL PRIMARY KEY,
    submit_command_id VARCHAR(128) NOT NULL UNIQUE,
    user_id VARCHAR(128) NOT NULL,
    account_type VARCHAR(32) NOT NULL,
    account_status VARCHAR(32) NOT NULL,
    registered_at VARCHAR(40) NOT NULL,
    eligibility_source_version BIGINT NOT NULL,
    eligibility_snapshot_at VARCHAR(40) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    planned_channel VARCHAR(256) NOT NULL,
    agreements_accepted BOOLEAN NOT NULL,
    submitted_at VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL,
    decided_by VARCHAR(128),
    decided_at VARCHAR(40),
    rejection_reason VARCHAR(2000),
    CONSTRAINT promotion_application_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT promotion_application_accepted CHECK (agreements_accepted = TRUE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE INDEX promotion_application_user ON promotion_application (user_id);
CREATE INDEX promotion_application_status_idx ON promotion_application (status, submitted_at);

CREATE TABLE promoter_account (
    promoter_user_id VARCHAR(128) NOT NULL PRIMARY KEY,
    invitation_code VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    CONSTRAINT promoter_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE promotion_command_receipt (
    command_id VARCHAR(128) NOT NULL PRIMARY KEY,
    command_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    semantics VARCHAR(4000) NOT NULL,
    recorded_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE referral_binding (
    referred_user_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL,
    command_id VARCHAR(128) NOT NULL UNIQUE,
    direct_inviter_user_id VARCHAR(128) NOT NULL,
    level1_promoter_user_id VARCHAR(128),
    level2_promoter_user_id VARCHAR(128),
    point_beneficiary_user_id VARCHAR(128),
    cash_promoter_user_id VARCHAR(128),
    cash_tier VARCHAR(16) NOT NULL,
    effective_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (referred_user_id, version),
    CONSTRAINT referral_positive_version CHECK (version > 0),
    CONSTRAINT referral_cash_tier CHECK (cash_tier IN ('NONE', 'LEVEL_1', 'LEVEL_2')),
    CONSTRAINT referral_cash_pair CHECK (
      (cash_tier = 'NONE' AND cash_promoter_user_id IS NULL) OR
      (cash_tier <> 'NONE' AND cash_promoter_user_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE INDEX referral_direct_inviter ON referral_binding (direct_inviter_user_id);
CREATE INDEX referral_level1 ON referral_binding (level1_promoter_user_id);
CREATE INDEX referral_level2 ON referral_binding (level2_promoter_user_id);

CREATE TABLE promotion_benefit_policy (
    version BIGINT NOT NULL PRIMARY KEY,
    level1_cash_rate6 DECIMAL(18, 6) NOT NULL,
    level2_cash_rate6 DECIMAL(18, 6) NOT NULL,
    CONSTRAINT promotion_policy_rates CHECK (
      level1_cash_rate6 >= 0 AND level1_cash_rate6 <= 1 AND
      level2_cash_rate6 >= 0 AND level2_cash_rate6 <= 1),
    CONSTRAINT promotion_policy_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

INSERT INTO promotion_benefit_policy
  (version, level1_cash_rate6, level2_cash_rate6)
VALUES (1, 0.100000, 0.050000);

CREATE TABLE promotion_commission_account (
    promoter_user_id VARCHAR(128) NOT NULL PRIMARY KEY,
    frozen4 DECIMAL(38, 4) NOT NULL DEFAULT 0,
    available4 DECIMAL(38, 4) NOT NULL DEFAULT 0,
    withdrawing4 DECIMAL(38, 4) NOT NULL DEFAULT 0,
    withdrawn4 DECIMAL(38, 4) NOT NULL DEFAULT 0,
    cash_debt4 DECIMAL(38, 4) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT promotion_commission_nonnegative CHECK (
      frozen4 >= 0 AND available4 >= 0 AND withdrawing4 >= 0 AND withdrawn4 >= 0 AND cash_debt4 >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE promotion_funding_benefit (
    funding_lot_id VARCHAR(128) NOT NULL PRIMARY KEY,
    source_event_id VARCHAR(128) NOT NULL UNIQUE,
    order_id VARCHAR(128) NOT NULL UNIQUE,
    wallet_owner_user_id VARCHAR(128) NOT NULL,
    referral_binding_version BIGINT NOT NULL,
    benefit_policy_version BIGINT NOT NULL,
    promoter_user_id VARCHAR(128) NOT NULL,
    cash_tier VARCHAR(16) NOT NULL,
    cash_rate6 DECIMAL(18, 6) NOT NULL,
    paid_amount4 DECIMAL(38, 4) NOT NULL,
    frozen_commission4 DECIMAL(38, 4) NOT NULL,
    released_commission4 DECIMAL(38, 4) NOT NULL DEFAULT 0,
    clawed_unreleased_commission4 DECIMAL(38, 4) NOT NULL DEFAULT 0,
    reversed_released_commission4 DECIMAL(38, 4) NOT NULL DEFAULT 0,
    settled_paid_amount4 DECIMAL(38, 4) NOT NULL DEFAULT 0,
    refunded_paid_amount4 DECIMAL(38, 4) NOT NULL DEFAULT 0,
    occurred_at VARCHAR(40) NOT NULL,
    CONSTRAINT promotion_funding_amounts CHECK (
      paid_amount4 > 0 AND frozen_commission4 > 0 AND
      released_commission4 >= 0 AND clawed_unreleased_commission4 >= 0 AND
      released_commission4 + clawed_unreleased_commission4 <= frozen_commission4 AND
      reversed_released_commission4 >= 0 AND
      reversed_released_commission4 <= released_commission4 AND
      settled_paid_amount4 >= 0 AND settled_paid_amount4 <= paid_amount4 AND
      refunded_paid_amount4 >= 0 AND refunded_paid_amount4 <= paid_amount4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE promotion_commission_ledger (
    promoter_user_id VARCHAR(128) NOT NULL,
    sequence_no BIGINT NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    amount4 DECIMAL(38, 4) NOT NULL,
    source_event_id VARCHAR(128) NOT NULL UNIQUE,
    order_id VARCHAR(128),
    funding_lot_id VARCHAR(128),
    allocation_id VARCHAR(128),
    withdrawal_id VARCHAR(128),
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (promoter_user_id, sequence_no),
    CONSTRAINT promotion_ledger_positive CHECK (amount4 > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE INDEX promotion_ledger_created ON promotion_commission_ledger (created_at);
CREATE INDEX promotion_ledger_lot ON promotion_commission_ledger (funding_lot_id);
CREATE UNIQUE INDEX promotion_ledger_allocation ON promotion_commission_ledger (allocation_id);

CREATE TABLE promotion_payout_account (
    payout_account_id VARCHAR(128) NOT NULL PRIMARY KEY,
    owner_user_id VARCHAR(128) NOT NULL,
    payout_type VARCHAR(32) NOT NULL,
    payout_account_name VARCHAR(256) NOT NULL,
    payout_account_reference VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    source_version BIGINT NOT NULL,
    last_source_event_id VARCHAR(128) NOT NULL UNIQUE,
    updated_at VARCHAR(40) NOT NULL,
    CONSTRAINT promotion_payout_account_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT promotion_payout_account_version CHECK (source_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE INDEX promotion_payout_account_owner ON promotion_payout_account (owner_user_id, status);

CREATE TABLE promotion_withdrawal (
    withdrawal_id VARCHAR(128) NOT NULL PRIMARY KEY,
    request_command_id VARCHAR(128) NOT NULL UNIQUE,
    promoter_user_id VARCHAR(128) NOT NULL,
    amount4 DECIMAL(38, 4) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payout_account_id VARCHAR(128) NOT NULL,
    payout_account_source_version BIGINT NOT NULL,
    payout_account_snapshot_at VARCHAR(40) NOT NULL,
    payout_type VARCHAR(32) NOT NULL,
    payout_account_name VARCHAR(256) NOT NULL,
    payout_account_reference VARCHAR(512) NOT NULL,
    rejection_reason VARCHAR(2000),
    requested_at VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    CONSTRAINT promotion_withdrawal_amount CHECK (amount4 >= 100),
    CONSTRAINT promotion_withdrawal_status CHECK (
      status IN ('WITHDRAWING', 'PENDING_PAYOUT', 'PAID', 'REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE INDEX promotion_withdrawal_promoter ON promotion_withdrawal (promoter_user_id, requested_at);
CREATE INDEX promotion_withdrawal_status_idx ON promotion_withdrawal (status, requested_at);

CREATE TABLE promotion_withdrawal_transition (
    command_id VARCHAR(128) NOT NULL PRIMARY KEY,
    withdrawal_id VARCHAR(128) NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    reason VARCHAR(2000),
    changed_at VARCHAR(40) NOT NULL,
    result_status VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
