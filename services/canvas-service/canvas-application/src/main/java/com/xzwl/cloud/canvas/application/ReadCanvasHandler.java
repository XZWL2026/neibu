package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.Canvas;
import java.util.Objects;

/** Reads the complete immutable canvas snapshot. */
public final class ReadCanvasHandler {

  private final CanvasStore store;

  public ReadCanvasHandler(CanvasStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public Canvas handle(String canvasId) {
    if (canvasId == null || canvasId.isBlank()) {
      throw new IllegalArgumentException("canvasId must not be blank");
    }
    return store.findById(canvasId)
        .filter(canvas -> !canvas.deleted())
        .orElseThrow(() -> new IllegalArgumentException("canvas not found: " + canvasId));
  }
}
