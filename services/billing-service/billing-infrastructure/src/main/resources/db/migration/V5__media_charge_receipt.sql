CREATE TABLE media_charge_lock (
  operation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY
);

CREATE TABLE media_charge_receipt (
  operation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  key_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_facts VARCHAR(2000) NOT NULL,
  amount4 DECIMAL(20,4) NOT NULL,
  rate4 DECIMAL(20,4) NOT NULL,
  points4 DECIMAL(20,4) NOT NULL
);
