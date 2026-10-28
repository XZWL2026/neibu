-- Upgrade existing catalogs without changing the checksum of the applied V6 migration.
ALTER TABLE media_price DROP CHECK media_price_chk_2;
ALTER TABLE media_price MODIFY COLUMN media_type VARCHAR(16) NOT NULL;
ALTER TABLE media_price ADD CONSTRAINT chk_media_price_type_unit CHECK (
  (media_type='IMAGE' AND unit='IMAGE')
  OR (media_type IN ('VIDEO','ENHANCEMENT') AND unit='SECOND')
);
