CREATE TABLE media_portrait (
  id VARCHAR(128) NOT NULL PRIMARY KEY,
  owner_hash CHAR(64) NOT NULL,
  owner_id VARCHAR(128) NOT NULL,
  file_id VARCHAR(128) NOT NULL,
  model VARCHAR(128) NOT NULL,
  reuse_hash CHAR(64) NOT NULL UNIQUE,
  provider_id VARCHAR(128) NOT NULL,
  provider_model VARCHAR(128) NOT NULL,
  generation_identity CHAR(64) NOT NULL,
  asset_identity CHAR(64) NOT NULL,
  project_name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  stage VARCHAR(32) NOT NULL,
  group_id VARCHAR(256),
  asset_id VARCHAR(256),
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
CREATE INDEX ix_portrait_owner ON media_portrait (owner_hash, created_at, id);
CREATE INDEX ix_portrait_worker ON media_portrait (status, updated_at);
CREATE TABLE media_portrait_request (
  request_hash CHAR(64) NOT NULL PRIMARY KEY,
  portrait_id VARCHAR(128) NOT NULL,
  FOREIGN KEY (portrait_id) REFERENCES media_portrait(id)
);
CREATE TABLE media_portrait_task (
  task_id VARCHAR(128) NOT NULL PRIMARY KEY,
  owner_hash CHAR(64) NOT NULL,
  mapping_json LONGTEXT NOT NULL
);
