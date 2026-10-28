package com.xzwl.cloud.media.infrastructure.task;
import com.baomidou.mybatisplus.annotation.*;
@TableName("media_task_result")
public class ResultRow {
  @TableId(type=IdType.INPUT) public String id;
  public String taskId;
  public Integer outputIndex;
  public String sourceUrl;
  public String fileId;
  public String failureCode;
}
