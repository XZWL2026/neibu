CREATE TABLE admin_debit_lock (
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY
) ENGINE=InnoDB;
CREATE TABLE admin_debit_record (
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    key_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    amount4 DECIMAL(38,4) NOT NULL,
    rate_snapshot4 DECIMAL(38,4) NOT NULL,
    points4 DECIMAL(38,4) NOT NULL,
    remark VARCHAR(2000) NOT NULL,
    recorded_at VARCHAR(40) NOT NULL,
    balance_after4 DECIMAL(38,4) NOT NULL,
    balance_version BIGINT NOT NULL,
    KEY debit_history(key_id, balance_version),
    CONSTRAINT debit_wallet FOREIGN KEY(key_id) REFERENCES wallet(wallet_id),
    CONSTRAINT debit_amount CHECK(amount4 > 0 AND points4 > 0 AND rate_snapshot4 > 0 AND balance_after4 >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
ALTER TABLE funding_allocation ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'CONSUME';
ALTER TABLE funding_allocation DROP INDEX allocation_source,
    ADD UNIQUE KEY allocation_source(wallet_id, kind, operation_id, lot_id);
