CREATE TABLE access_account_lock (
  owner_id VARCHAR(128) PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE access_key (
  key_id VARCHAR(128) PRIMARY KEY,
  owner_id VARCHAR(128) NOT NULL,
  account_type VARCHAR(16) NOT NULL,
  key_type VARCHAR(16) NOT NULL,
  secret_hash CHAR(64) NOT NULL,
  encrypted_secret VARCHAR(512) NOT NULL,
  paid_order_id VARCHAR(128) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT true,
  UNIQUE KEY one_primary_per_owner(owner_id),
  UNIQUE KEY unique_key_hash(secret_hash),
  UNIQUE KEY first_paid_order(paid_order_id),
  CONSTRAINT access_key_type CHECK (
    (account_type='API' AND key_type='API') OR (account_type IN ('PERSONAL','ENTERPRISE') AND key_type='MEDIA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
