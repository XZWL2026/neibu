CREATE TABLE media_task (
  id VARCHAR(128) NOT NULL PRIMARY KEY,
  owner_id VARCHAR(128) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  task_json LONGTEXT NOT NULL,
  created_at BIGINT NOT NULL,
  next_run_at BIGINT NOT NULL,
  lease_token VARCHAR(128),
  lease_until BIGINT,
  version BIGINT NOT NULL,
  CONSTRAINT uq_media_request UNIQUE (owner_id, idempotency_key)
);
CREATE INDEX ix_media_due ON media_task (status, next_run_at, lease_until);
CREATE INDEX ix_media_owner ON media_task (owner_id, id);

CREATE TABLE media_task_state (
  id VARCHAR(160) NOT NULL PRIMARY KEY,
  task_id VARCHAR(128) NOT NULL,
  version BIGINT NOT NULL,
  from_status VARCHAR(32),
  to_status VARCHAR(32) NOT NULL,
  occurred_at BIGINT NOT NULL,
  CONSTRAINT uq_media_state UNIQUE (task_id, version),
  CONSTRAINT fk_media_state_task FOREIGN KEY (task_id) REFERENCES media_task(id)
);

CREATE TABLE media_task_result (
  id VARCHAR(160) NOT NULL PRIMARY KEY,
  task_id VARCHAR(128) NOT NULL,
  output_index INT NOT NULL,
  source_url VARCHAR(8192),
  file_id VARCHAR(128),
  failure_code VARCHAR(128),
  CONSTRAINT uq_media_output UNIQUE (task_id, output_index),
  CONSTRAINT fk_media_result_task FOREIGN KEY (task_id) REFERENCES media_task(id)
);
