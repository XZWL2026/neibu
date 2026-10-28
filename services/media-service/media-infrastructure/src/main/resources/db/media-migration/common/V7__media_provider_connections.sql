CREATE TABLE media_provider_connection (
  provider_id VARCHAR(128) NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL,
  base_url VARCHAR(2048) NOT NULL,
  api_key TEXT,
  encrypted_api_key TEXT NOT NULL,
  CHECK (version > 0)
);
INSERT INTO media_provider_connection(provider_id,version,base_url,api_key,encrypted_api_key)
SELECT provider_id,version,base_url,api_key,encrypted_api_key FROM media_ark_connection;
