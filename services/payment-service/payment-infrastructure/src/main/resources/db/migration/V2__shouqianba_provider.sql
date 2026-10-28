CREATE TABLE payment_provider_state (
  id VARCHAR(160) PRIMARY KEY,
  payload MEDIUMTEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
ALTER TABLE payment_order MODIFY checkout_url TEXT;
ALTER TABLE payment_work DROP CHECK work_stage;
ALTER TABLE payment_work ADD CONSTRAINT work_stage CHECK(stage IN ('POLL','FULFILL','REFUND'));
