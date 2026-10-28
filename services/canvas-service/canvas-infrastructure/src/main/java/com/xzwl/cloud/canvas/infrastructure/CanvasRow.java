package com.xzwl.cloud.canvas.infrastructure;

import com.baomidou.mybatisplus.annotation.*;
import java.sql.Timestamp;

@TableName("canvas_document")
public class CanvasRow {
  @TableId(type = IdType.INPUT) public String id;
  public String ownerId;
  public String name;
  public String contentJson;
  public long version;
  public Timestamp createdAt;
  public Timestamp updatedAt;
  public boolean deleted;
}
