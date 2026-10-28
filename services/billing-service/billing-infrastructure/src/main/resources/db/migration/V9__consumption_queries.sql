-- New charges retain the account classification used to price them. Historical values stay unknown.
ALTER TABLE media_charge_receipt ADD COLUMN account_type VARCHAR(16) NULL;
CREATE INDEX ledger_consumption_source ON wallet_ledger(wallet_id,source_id,kind);
CREATE INDEX ledger_consumption_time ON wallet_ledger(kind,recorded_at,entry_id);
