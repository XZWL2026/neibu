ALTER TABLE media_provider_operation ADD COLUMN failure_stage VARCHAR(32);
ALTER TABLE media_provider_operation ADD COLUMN failure_code VARCHAR(256);
ALTER TABLE media_provider_operation ADD COLUMN failure_status VARCHAR(32);
