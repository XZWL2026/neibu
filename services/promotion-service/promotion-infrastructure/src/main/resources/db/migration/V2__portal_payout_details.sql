ALTER TABLE promotion_payout_account ADD COLUMN bank_name VARCHAR(256);
ALTER TABLE promotion_payout_account ADD COLUMN qr_code_reference VARCHAR(512);
ALTER TABLE promotion_commission_ledger ADD COLUMN successful_paid_amount4 DECIMAL(38,4);
