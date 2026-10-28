CREATE TABLE media_ark_connection (
  id INTEGER NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL,
  provider_id VARCHAR(128) NOT NULL,
  base_url VARCHAR(2048) NOT NULL,
  encrypted_api_key TEXT NOT NULL,
  CHECK (id = 1),
  CHECK (version > 0)
);
