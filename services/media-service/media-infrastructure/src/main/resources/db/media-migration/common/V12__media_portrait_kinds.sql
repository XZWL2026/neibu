ALTER TABLE media_portrait ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'IMAGE';
ALTER TABLE media_portrait ADD COLUMN duration_seconds DOUBLE;
