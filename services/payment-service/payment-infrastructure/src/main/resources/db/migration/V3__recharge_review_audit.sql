ALTER TABLE payment_order
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'PROVIDER',
  ADD COLUMN remark VARCHAR(1000),
  ADD COLUMN submitted_by VARCHAR(128),
  ADD COLUMN confirmed_by VARCHAR(128),
  DROP CHECK payment_status,
  ADD CONSTRAINT payment_status CHECK (status IN ('PENDING','REVIEW_PENDING','EXPIRED','PAID','REFUND_PENDING','REFUNDED')),
  ADD CONSTRAINT payment_source CHECK (source IN ('PROVIDER','MANUAL')),
  ADD CONSTRAINT manual_review_audit CHECK (source <> 'MANUAL' OR (submitted_by IS NOT NULL AND remark IS NOT NULL AND transaction_id IS NULL AND provider_order_id IS NULL AND refund_reference IS NULL)),
  ADD CONSTRAINT manual_confirmation_audit CHECK (source <> 'MANUAL' OR status = 'REVIEW_PENDING' OR (status = 'PAID' AND confirmed_by IS NOT NULL AND paid_at IS NOT NULL)),
  ADD INDEX recharge_history(created_at,id);
