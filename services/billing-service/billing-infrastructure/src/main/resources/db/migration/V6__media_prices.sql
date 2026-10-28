CREATE TABLE media_price_catalog (
  id INT PRIMARY KEY,
  version BIGINT NOT NULL,
  managed BOOLEAN NOT NULL
);
INSERT INTO media_price_catalog(id,version,managed) VALUES(1,1,FALSE);
CREATE TABLE media_price (
  media_type VARCHAR(8) NOT NULL,
  account_type VARCHAR(16) NOT NULL,
  model VARCHAR(128) NOT NULL,
  resolution VARCHAR(128) NOT NULL,
  unit VARCHAR(8) NOT NULL,
  points_per_unit DECIMAL(16,4) NOT NULL,
  enabled BOOLEAN NOT NULL,
  PRIMARY KEY(media_type,account_type,model,resolution),
  CHECK(points_per_unit >= 0),
  CHECK((media_type='IMAGE' AND unit='IMAGE') OR (media_type='VIDEO' AND unit='SECOND'))
);
