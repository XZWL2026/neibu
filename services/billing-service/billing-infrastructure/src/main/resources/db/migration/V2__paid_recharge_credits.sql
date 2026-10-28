CREATE TABLE recharge_credit_record (
    order_id VARCHAR(128) NOT NULL PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    wallet_id VARCHAR(128) NOT NULL,
    key_id VARCHAR(128) NOT NULL,
    amount4 DECIMAL(38, 4) NOT NULL,
    rate_snapshot4 DECIMAL(38, 4) NOT NULL,
    points4 DECIMAL(38, 4) NOT NULL,
    paid_at VARCHAR(40) NOT NULL,
    balance_after4 DECIMAL(38, 4) NOT NULL,
    balance_version BIGINT NOT NULL,
    CONSTRAINT recharge_credit_wallet FOREIGN KEY (wallet_id) REFERENCES wallet (wallet_id),
    CONSTRAINT recharge_positive_amount CHECK (amount4 > 0),
    CONSTRAINT recharge_positive_rate CHECK (rate_snapshot4 > 0),
    CONSTRAINT recharge_positive_points CHECK (points4 > 0),
    CONSTRAINT recharge_nonnegative_balance CHECK (balance_after4 >= 0),
    CONSTRAINT recharge_positive_version CHECK (balance_version > 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE funding_lot (
    lot_id VARCHAR(256) NOT NULL PRIMARY KEY,
    wallet_id VARCHAR(128) NOT NULL,
    lot_type VARCHAR(32) NOT NULL,
    original_points4 DECIMAL(38, 4) NOT NULL,
    remaining_points4 DECIMAL(38, 4) NOT NULL,
    credited_at VARCHAR(40) NOT NULL,
    benefit_eligible BOOLEAN NOT NULL,
    CONSTRAINT funding_lot_wallet FOREIGN KEY (wallet_id) REFERENCES wallet (wallet_id),
    CONSTRAINT funding_lot_type CHECK (lot_type IN ('GIFT', 'REAL_RECHARGE', 'REFERRAL_REWARD')),
    CONSTRAINT funding_lot_valid_balance CHECK (
        original_points4 >= 0 AND remaining_points4 >= 0 AND remaining_points4 <= original_points4),
    CONSTRAINT funding_lot_benefit_source CHECK (lot_type = 'REAL_RECHARGE' OR benefit_eligible = false)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
