ALTER TABLE media_task ADD COLUMN operation VARCHAR(32) NOT NULL DEFAULT 'GENERATION';
CREATE TABLE media_enhancement_attempt (
 task_id VARCHAR(36) NOT NULL PRIMARY KEY,
 owner_id VARCHAR(128) NOT NULL,source_task_id VARCHAR(36) NOT NULL,output_index INT NOT NULL,
 source_file_id VARCHAR(128) NOT NULL,target_resolution VARCHAR(16) NOT NULL,quote_json LONGTEXT NOT NULL
);
CREATE INDEX idx_enhancement_source_target ON media_enhancement_attempt(owner_id,source_task_id,output_index,target_resolution);
CREATE TABLE media_enhancement_request (
 owner_id VARCHAR(128) NOT NULL,request_key VARCHAR(128) NOT NULL,source_task_id VARCHAR(36) NOT NULL,
 output_index INT NOT NULL,quote_id VARCHAR(36) NOT NULL,task_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(owner_id,request_key)
);
CREATE TABLE media_task_request_lock (
 owner_id VARCHAR(128) NOT NULL,request_key VARCHAR(128) NOT NULL,
 PRIMARY KEY(owner_id,request_key)
);
