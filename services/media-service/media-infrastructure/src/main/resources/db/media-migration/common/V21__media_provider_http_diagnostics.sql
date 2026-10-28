CREATE TABLE media_provider_http_diagnostic (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  operation_id VARCHAR(128) NOT NULL,
  phase VARCHAR(32) NOT NULL,
  created_at BIGINT NOT NULL,
  detail_json MEDIUMTEXT NOT NULL
);
CREATE INDEX idx_provider_http_diagnostic_operation ON media_provider_http_diagnostic(operation_id, created_at);
CREATE INDEX idx_provider_http_diagnostic_created ON media_provider_http_diagnostic(created_at);
