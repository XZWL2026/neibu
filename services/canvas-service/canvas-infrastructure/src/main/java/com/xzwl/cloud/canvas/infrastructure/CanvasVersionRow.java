package com.xzwl.cloud.canvas.infrastructure;

import com.baomidou.mybatisplus.annotation.*;
import java.sql.Timestamp;

@TableName("canvas_version")
public class CanvasVersionRow {
  @TableId(type = IdType.INPUT) public String id;
  public String canvasId;
  public long version;
  public String contentJson;
  public boolean deleted;
  public Timestamp savedAt;
}
