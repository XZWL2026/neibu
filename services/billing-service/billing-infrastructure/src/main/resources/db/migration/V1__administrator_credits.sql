CREATE TABLE wallet (
    wallet_id VARCHAR(128) NOT NULL PRIMARY KEY,
    available_points4 DECIMAL(38, 4) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT wallet_nonnegative_balance CHECK (available_points4 >= 0),
    CONSTRAINT wallet_positive_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE admin_credit_record (
    request_id VARCHAR(128) NOT NULL PRIMARY KEY,
    wallet_id VARCHAR(128) NOT NULL,
    key_id VARCHAR(128) NOT NULL,
    points4 DECIMAL(38, 4) NOT NULL,
    admin_id VARCHAR(128) NOT NULL,
    -- Preserve the full Instant: operation time participates in existing replay equality.
    recorded_at VARCHAR(40) NOT NULL,
    remark VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCEEDED',
    balance_after4 DECIMAL(38, 4) NOT NULL,
    balance_version BIGINT NOT NULL,
    CONSTRAINT admin_credit_wallet FOREIGN KEY (wallet_id) REFERENCES wallet (wallet_id),
    CONSTRAINT admin_credit_positive_points CHECK (points4 > 0),
    CONSTRAINT admin_credit_success CHECK (status = 'SUCCEEDED'),
    CONSTRAINT admin_credit_nonnegative_balance CHECK (balance_after4 >= 0),
    CONSTRAINT admin_credit_positive_version CHECK (balance_version > 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
