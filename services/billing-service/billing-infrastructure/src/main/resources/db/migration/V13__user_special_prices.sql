ALTER TABLE api_media_price_override ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT 'API';
ALTER TABLE api_media_price_override DROP PRIMARY KEY;
ALTER TABLE api_media_price_override ADD PRIMARY KEY (owner_id,account_type,media_type,model,resolution);
ALTER TABLE api_media_price_audit ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT 'API';
