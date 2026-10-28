ALTER TABLE media_provider_connection ADD COLUMN timeout_seconds INTEGER NOT NULL DEFAULT 180;
ALTER TABLE media_provider_connection ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
