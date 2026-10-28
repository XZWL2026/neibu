CREATE TABLE media_provider_operation (
  operation_id VARCHAR(128) NOT NULL PRIMARY KEY,
  phase VARCHAR(32) NOT NULL,
  plan_json LONGTEXT NOT NULL,
  upstream_id VARCHAR(256),
  source_url VARCHAR(8192),
  outputs_json LONGTEXT
);
