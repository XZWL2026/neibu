package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.CanvasContent;
import java.time.Instant;

/** Application input for replacing a complete canvas snapshot. */
public record SaveCanvasCommand(
    String canvasId, long expectedVersion, CanvasContent content, Instant savedAt, String name) {
  public SaveCanvasCommand(String canvasId, long expectedVersion, CanvasContent content, Instant savedAt) {
    this(canvasId, expectedVersion, content, savedAt, null);
  }
}
