CREATE TABLE wallet_ledger (
    entry_id VARCHAR(40) PRIMARY KEY,
    wallet_id VARCHAR(128) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    points4 DECIMAL(38,4) NOT NULL,
    balance_after4 DECIMAL(38,4) NOT NULL,
    balance_version BIGINT NOT NULL,
    recorded_at VARCHAR(40) NOT NULL,
    UNIQUE KEY ledger_request (wallet_id, kind, request_id),
    UNIQUE KEY ledger_sequence (wallet_id, balance_version),
    CONSTRAINT ledger_wallet FOREIGN KEY (wallet_id) REFERENCES wallet(wallet_id),
    CONSTRAINT ledger_balance CHECK (balance_after4 >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE funding_allocation (
    id VARCHAR(40) PRIMARY KEY,
    wallet_id VARCHAR(128) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    lot_id VARCHAR(256) NOT NULL,
    points4 DECIMAL(38,4) NOT NULL,
    refunded_points4 DECIMAL(38,4) NOT NULL DEFAULT 0,
    UNIQUE KEY allocation_source (wallet_id, operation_id, lot_id),
    CONSTRAINT allocation_lot FOREIGN KEY(lot_id) REFERENCES funding_lot(lot_id),
    CONSTRAINT allocation_refund CHECK (points4 > 0 AND refunded_points4 BETWEEN 0 AND points4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
-- Make already persisted administrator credits spendable without fabricating cash payments.
INSERT INTO funding_lot(lot_id,wallet_id,lot_type,original_points4,remaining_points4,credited_at,benefit_eligible)
 SELECT CONCAT('admin:',request_id),wallet_id,'GIFT',points4,points4,recorded_at,false FROM admin_credit_record;
INSERT INTO wallet_ledger(entry_id,wallet_id,request_id,kind,source_id,points4,balance_after4,balance_version,recorded_at)
 SELECT UUID(),wallet_id,request_id,'ADMIN_CREDIT',key_id,points4,balance_after4,balance_version,recorded_at FROM admin_credit_record;
INSERT INTO wallet_ledger(entry_id,wallet_id,request_id,kind,source_id,points4,balance_after4,balance_version,recorded_at)
 SELECT UUID(),wallet_id,order_id,'RECHARGE',order_id,points4,balance_after4,balance_version,paid_at FROM recharge_credit_record;
