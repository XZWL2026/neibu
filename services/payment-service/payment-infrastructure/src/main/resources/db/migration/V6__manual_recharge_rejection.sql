ALTER TABLE payment_order
  ADD COLUMN rejected_by VARCHAR(128),
  ADD COLUMN rejected_at VARCHAR(40),
  DROP CHECK payment_status,
  ADD CONSTRAINT payment_status CHECK (status IN ('PENDING','REVIEW_PENDING','REJECTED','EXPIRED','PAID','REFUND_PENDING','REFUNDED')),
  DROP CHECK manual_confirmation_audit,
  ADD CONSTRAINT manual_confirmation_audit CHECK (source <> 'MANUAL' OR status = 'REVIEW_PENDING' OR (status = 'PAID' AND confirmed_by IS NOT NULL AND paid_at IS NOT NULL) OR (status = 'REJECTED' AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL)),
  ADD CONSTRAINT manual_rejection_audit CHECK ((status = 'REJECTED' AND source = 'MANUAL' AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND confirmed_by IS NULL AND paid_at IS NULL AND fulfilled = FALSE) OR (status <> 'REJECTED' AND rejected_by IS NULL AND rejected_at IS NULL));
