CREATE TABLE media_product_suite (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  owner_id VARCHAR(128) NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  request_json LONGTEXT NOT NULL,
  created_at BIGINT NOT NULL,
  UNIQUE (owner_id, request_key)
);
CREATE INDEX media_product_suite_owner_time ON media_product_suite(owner_id, created_at, id);
CREATE TABLE media_product_suite_item (
  suite_id VARCHAR(36) NOT NULL,
  module VARCHAR(32) NOT NULL,
  attempt INTEGER NOT NULL,
  task_id VARCHAR(36) NOT NULL,
  source_task_id VARCHAR(36),
  PRIMARY KEY (suite_id, module, attempt),
  UNIQUE (task_id)
);
CREATE TABLE media_product_copy_budget (
  owner_id VARCHAR(128) NOT NULL,
  budget_day VARCHAR(10) NOT NULL,
  attempts INTEGER NOT NULL,
  PRIMARY KEY (owner_id, budget_day)
);
