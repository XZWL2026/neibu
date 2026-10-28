CREATE INDEX ix_recharge_credit_user ON recharge_credit_record(user_id);
CREATE INDEX ix_media_charge_owner ON media_charge_receipt(owner_id,key_id,operation_id);
