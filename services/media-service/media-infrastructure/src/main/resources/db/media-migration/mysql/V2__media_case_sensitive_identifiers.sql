-- Stable identity and idempotency must not inherit a case-insensitive schema default.
ALTER TABLE media_task_state DROP FOREIGN KEY fk_media_state_task;
ALTER TABLE media_task_result DROP FOREIGN KEY fk_media_result_task;
ALTER TABLE media_task CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
ALTER TABLE media_task_state CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
ALTER TABLE media_task_result CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
ALTER TABLE media_task_state ADD CONSTRAINT fk_media_state_task FOREIGN KEY (task_id) REFERENCES media_task(id);
ALTER TABLE media_task_result ADD CONSTRAINT fk_media_result_task FOREIGN KEY (task_id) REFERENCES media_task(id);
