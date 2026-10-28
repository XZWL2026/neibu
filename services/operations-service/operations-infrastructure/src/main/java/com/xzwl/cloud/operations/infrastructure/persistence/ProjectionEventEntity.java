package com.xzwl.cloud.operations.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("operations_projection_event")
public class ProjectionEventEntity {
  @TableId(type = IdType.INPUT)
  public String eventId;
  public String recordType;
  public String recordId;
  public String semantics;
  public String projectedAt;
}
