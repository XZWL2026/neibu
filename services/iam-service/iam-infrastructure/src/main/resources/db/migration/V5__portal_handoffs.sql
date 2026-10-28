CREATE TABLE portal_handoff (
  ticket_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  challenge CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_session VARCHAR(128) NOT NULL,
  credential_id VARCHAR(128) NOT NULL,
  login_name VARCHAR(64) NOT NULL,
  role VARCHAR(16) NOT NULL,
  credential_version BIGINT NOT NULL,
  user_id VARCHAR(128) NOT NULL,
  user_version BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  INDEX portal_handoff_expiry (expires_at),
  INDEX portal_handoff_source (source_session)
);
