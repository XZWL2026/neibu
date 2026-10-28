CREATE TABLE routing_provider (
  provider_id VARCHAR(64) PRIMARY KEY,
  display_name VARCHAR(128) NOT NULL,
  endpoint VARCHAR(512) NOT NULL,
  enabled BOOLEAN NOT NULL,
  configuration_json TEXT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE routing_model_config (
  model_id VARCHAR(128) PRIMARY KEY,
  provider_id VARCHAR(64) NOT NULL,
  provider_model VARCHAR(128) NOT NULL,
  configuration_json TEXT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_routing_model_provider
    FOREIGN KEY (provider_id) REFERENCES routing_provider(provider_id)
);

CREATE TABLE routing_task_snapshot (
  task_id VARCHAR(128) PRIMARY KEY,
  requested_model VARCHAR(128) NOT NULL,
  provider_id VARCHAR(64) NOT NULL,
  provider_display_name VARCHAR(128) NOT NULL,
  provider_endpoint VARCHAR(512) NOT NULL,
  provider_model VARCHAR(128) NOT NULL,
  provider_configuration_json TEXT NOT NULL,
  model_configuration_json TEXT NOT NULL,
  selected_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_routing_task_snapshot_model
  ON routing_task_snapshot(requested_model, selected_at);
