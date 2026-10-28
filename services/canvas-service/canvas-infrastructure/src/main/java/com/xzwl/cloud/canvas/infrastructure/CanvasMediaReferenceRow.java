package com.xzwl.cloud.canvas.infrastructure;

import com.baomidou.mybatisplus.annotation.*;

@TableName("canvas_media_reference")
public class CanvasMediaReferenceRow {
  @TableId(type = IdType.INPUT) public String id;
  public String canvasId;
  public long version;
  public String nodeId;
  public String nodeKind;
  public String propertyName;
  public String referenceValue;
}
