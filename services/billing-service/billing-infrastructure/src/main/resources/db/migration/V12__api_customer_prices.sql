CREATE TABLE api_media_price_catalog (
  owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE api_media_price_override (
  owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  media_type VARCHAR(16) NOT NULL,
  model VARCHAR(128) NOT NULL,
  resolution VARCHAR(128) NOT NULL,
  yuan_per_unit DECIMAL(20,4) NOT NULL,
  PRIMARY KEY (owner_id,media_type,model,resolution)
);
CREATE TABLE api_media_price_audit (
  request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  owner_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operator_id VARCHAR(128) NOT NULL,
  expected_version BIGINT NOT NULL,
  version BIGINT NOT NULL,
  prices_json MEDIUMTEXT NOT NULL,
  snapshot_json MEDIUMTEXT NOT NULL,
  changed_at VARCHAR(40) NOT NULL,
  KEY api_price_owner (owner_id,version)
);

ALTER TABLE media_charge_receipt ADD COLUMN charged_at VARCHAR(40) NULL;
CREATE INDEX media_charge_owner_time ON media_charge_receipt(owner_id,charged_at);
