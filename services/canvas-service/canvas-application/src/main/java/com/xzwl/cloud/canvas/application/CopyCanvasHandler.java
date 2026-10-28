package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.Canvas;
import java.util.Objects;

/** Copies a canvas snapshot without creating a media job or sharing aggregate identity. */
public final class CopyCanvasHandler {

  private final CanvasStore store;

  public CopyCanvasHandler(CanvasStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public Canvas handle(CopyCanvasCommand command) {
    CopyCanvasCommand requested = Objects.requireNonNull(command, "command");
    Canvas source = new ReadCanvasHandler(store).handle(requested.sourceCanvasId());
    Canvas copy = source.copy(requested.newCanvasId(), requested.newName(), requested.copiedAt());
    store.save(copy);
    return copy;
  }
}
