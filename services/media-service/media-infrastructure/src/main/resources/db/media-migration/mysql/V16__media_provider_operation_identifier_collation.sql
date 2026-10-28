-- 与 media_task.id 保持相同的区分大小写排序规则，避免统计关联查询失败。
ALTER TABLE media_provider_operation MODIFY operation_id VARCHAR(128)
  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '业务操作标识';
