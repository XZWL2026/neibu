ALTER TABLE payment_order
  DROP CHECK payment_source,
  ADD CONSTRAINT payment_source CHECK (source IN ('PROVIDER','MANUAL','CORPORATE')),
  DROP CHECK manual_review_audit,
  ADD CONSTRAINT manual_review_audit CHECK (source NOT IN ('MANUAL','CORPORATE') OR (submitted_by IS NOT NULL AND remark IS NOT NULL AND transaction_id IS NULL AND provider_order_id IS NULL AND refund_reference IS NULL)),
  DROP CHECK manual_confirmation_audit,
  ADD CONSTRAINT manual_confirmation_audit CHECK (source NOT IN ('MANUAL','CORPORATE') OR status = 'REVIEW_PENDING' OR (status = 'PAID' AND confirmed_by IS NOT NULL AND paid_at IS NOT NULL) OR (status = 'REJECTED' AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL)),
  DROP CHECK manual_rejection_audit,
  ADD CONSTRAINT manual_rejection_audit CHECK ((status = 'REJECTED' AND source IN ('MANUAL','CORPORATE') AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND confirmed_by IS NULL AND paid_at IS NULL AND fulfilled = FALSE) OR (status <> 'REJECTED' AND rejected_by IS NULL AND rejected_at IS NULL)),
  ADD CONSTRAINT corporate_account CHECK (source <> 'CORPORATE' OR recharge_type='API'),
  ADD INDEX corporate_history(source,created_at,id);
CREATE TABLE payment_transfer_proof (
  order_id VARCHAR(128) PRIMARY KEY,
  file_name VARCHAR(200) NOT NULL,
  content_type VARCHAR(64) NOT NULL,
  size_bytes INT NOT NULL,
  content MEDIUMBLOB NOT NULL,
  CONSTRAINT transfer_proof_order FOREIGN KEY (order_id) REFERENCES payment_order(id),
  CONSTRAINT transfer_proof_size CHECK (size_bytes BETWEEN 1 AND 10485760),
  CONSTRAINT transfer_proof_type CHECK (content_type IN ('image/jpeg','image/png','application/pdf'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
