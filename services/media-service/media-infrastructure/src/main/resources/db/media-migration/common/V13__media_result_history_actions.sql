ALTER TABLE media_task ADD COLUMN history_deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE media_task ADD COLUMN regeneration_source_id VARCHAR(128);
CREATE UNIQUE INDEX uq_media_regeneration_source ON media_task (regeneration_source_id);
