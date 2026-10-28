package com.xzwl.cloud.media.infrastructure.task;
import com.baomidou.mybatisplus.annotation.*;
@TableName("media_task")
public class TaskRow {
  @TableId(type=IdType.INPUT) public String id;
  public String ownerId;
  public String idempotencyKey;
  public String status;
  public String operation;
  public Boolean historyDeleted;
  public String regenerationSourceId;
  public String taskJson;
  public Long createdAt;
  public Long nextRunAt;
  public String leaseToken;
  public Long leaseUntil;
  public Long version;
}
