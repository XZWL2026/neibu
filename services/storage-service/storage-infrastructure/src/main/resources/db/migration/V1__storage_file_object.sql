CREATE TABLE storage_file_object (
  file_id VARCHAR(128) PRIMARY KEY,
  owner_id VARCHAR(128) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  mime_type VARCHAR(64) NOT NULL,
  size_bytes BIGINT NOT NULL,
  etag VARCHAR(256) NOT NULL,
  purpose VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT uk_storage_file_object_key UNIQUE (object_key),
  INDEX idx_storage_file_owner_created (owner_id, created_at)
);
