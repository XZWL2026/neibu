CREATE TABLE media_enhancement_quote (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  owner_id VARCHAR(128) NOT NULL,
  expires_at BIGINT NOT NULL,
  quote_json LONGTEXT NOT NULL
);
CREATE INDEX idx_enhancement_quote_expiry ON media_enhancement_quote(expires_at);
