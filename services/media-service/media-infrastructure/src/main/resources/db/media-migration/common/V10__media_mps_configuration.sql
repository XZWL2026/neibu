CREATE TABLE media_mps_configuration (
  id INT NOT NULL PRIMARY KEY,
  version BIGINT NOT NULL,
  secret_id VARCHAR(256) NOT NULL,
  secret_key VARCHAR(4096) NOT NULL,
  region VARCHAR(64) NOT NULL,
  bucket VARCHAR(128) NOT NULL,
  bucket_region VARCHAR(64) NOT NULL,
  output_dir VARCHAR(512) NOT NULL,
  template_720p BIGINT NOT NULL,
  template_1080p BIGINT NOT NULL,
  template_4k BIGINT NOT NULL
);
