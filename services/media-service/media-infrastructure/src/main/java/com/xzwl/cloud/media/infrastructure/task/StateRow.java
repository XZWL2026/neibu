package com.xzwl.cloud.media.infrastructure.task;
import com.baomidou.mybatisplus.annotation.*;
@TableName("media_task_state")
public class StateRow {
  @TableId(type=IdType.INPUT) public String id;
  public String taskId;
  public Long version;
  public String fromStatus;
  public String toStatus;
  public Long occurredAt;
}
