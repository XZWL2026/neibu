ALTER TABLE promotion_benefit_policy ADD COLUMN point_rate6 DECIMAL(18,6) NOT NULL DEFAULT 0.100000;
ALTER TABLE promotion_benefit_policy ADD COLUMN effective_at VARCHAR(40) NOT NULL DEFAULT '1970-01-01T00:00:00.000000000Z';
ALTER TABLE promotion_benefit_policy ADD COLUMN operator_id VARCHAR(128) NOT NULL DEFAULT 'system';
ALTER TABLE promotion_benefit_policy ADD COLUMN reason VARCHAR(2000) NOT NULL DEFAULT '原始服务初始化比例';
ALTER TABLE promotion_benefit_policy ADD COLUMN change_scope VARCHAR(32) NOT NULL DEFAULT 'GLOBAL';
ALTER TABLE promotion_benefit_policy ADD COLUMN changed_promoter_id VARCHAR(128);
ALTER TABLE promotion_benefit_policy ADD COLUMN command_id VARCHAR(128) UNIQUE;
ALTER TABLE promotion_benefit_policy ADD CONSTRAINT promotion_policy_point CHECK (point_rate6 >= 0 AND point_rate6 <= 1);
CREATE UNIQUE INDEX promotion_policy_effective ON promotion_benefit_policy(effective_at);

CREATE TABLE promotion_benefit_override (
  policy_version BIGINT NOT NULL,
  promoter_user_id VARCHAR(128) NOT NULL,
  level1_cash_rate6 DECIMAL(18,6),
  level2_cash_rate6 DECIMAL(18,6),
  PRIMARY KEY(policy_version,promoter_user_id),
  CONSTRAINT promotion_override_rates CHECK (
    (level1_cash_rate6 IS NULL OR level1_cash_rate6 BETWEEN 0 AND 1) AND
    (level2_cash_rate6 IS NULL OR level2_cash_rate6 BETWEEN 0 AND 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

ALTER TABLE promotion_funding_benefit DROP CONSTRAINT promotion_funding_amounts;
ALTER TABLE promotion_funding_benefit ADD CONSTRAINT promotion_funding_amounts CHECK (
  paid_amount4 > 0 AND frozen_commission4 >= 0 AND
  released_commission4 >= 0 AND clawed_unreleased_commission4 >= 0 AND
  released_commission4 + clawed_unreleased_commission4 <= frozen_commission4 AND
  reversed_released_commission4 >= 0 AND reversed_released_commission4 <= released_commission4 AND
  settled_paid_amount4 >= 0 AND settled_paid_amount4 <= paid_amount4 AND
  refunded_paid_amount4 >= 0 AND refunded_paid_amount4 <= paid_amount4);
ALTER TABLE promotion_commission_ledger DROP CONSTRAINT promotion_ledger_positive;
ALTER TABLE promotion_commission_ledger ADD CONSTRAINT promotion_ledger_positive CHECK (
  amount4 > 0 OR (amount4 = 0 AND action_type IN ('COMMISSION_FROZEN','COMMISSION_RELEASED','RECHARGE_REFUND_CLAWBACK')));

CREATE TABLE promotion_rate_correction (
  order_id VARCHAR(128) NOT NULL PRIMARY KEY,
  old_policy_version BIGINT NOT NULL,
  new_policy_version BIGINT NOT NULL,
  old_rate6 DECIMAL(18,6) NOT NULL,
  new_rate6 DECIMAL(18,6) NOT NULL,
  old_commission4 DECIMAL(38,4) NOT NULL,
  new_commission4 DECIMAL(38,4) NOT NULL,
  reason VARCHAR(2000) NOT NULL,
  operator_id VARCHAR(128) NOT NULL,
  corrected_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
