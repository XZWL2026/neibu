ALTER TABLE payment_order ADD COLUMN payment_method VARCHAR(16),
  ADD CONSTRAINT payment_method_value CHECK (payment_method IS NULL OR payment_method IN ('ALIPAY','WECHAT'));
