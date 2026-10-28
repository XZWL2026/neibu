ALTER TABLE payment_account ADD recharge_completed BOOLEAN NOT NULL DEFAULT false, ADD pending_request_id VARCHAR(128) NULL;
UPDATE payment_account SET recharge_completed=true WHERE locked_type IS NOT NULL;
CREATE TABLE payment_account_change (
 request_id VARCHAR(128) PRIMARY KEY,user_id VARCHAR(128) NOT NULL,account_type VARCHAR(16) NOT NULL,
 create_key BOOLEAN NOT NULL,operator_id VARCHAR(128) NOT NULL,completed BOOLEAN NOT NULL DEFAULT false,
 recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),completed_at TIMESTAMP(6) NULL,
 KEY account_changes_pending(completed,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
