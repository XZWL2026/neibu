package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.Canvas;
import java.util.Objects;

/** Applies a complete snapshot replacement with optimistic version protection. */
public final class SaveCanvasHandler {

  private final CanvasStore store;

  public SaveCanvasHandler(CanvasStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public Canvas handle(SaveCanvasCommand command) {
    SaveCanvasCommand requested = Objects.requireNonNull(command, "command");
    Canvas current = new ReadCanvasHandler(store).handle(requested.canvasId());
    Canvas updated = current.save(requested.content(), requested.expectedVersion(), requested.savedAt(),
        requested.name() == null ? current.name() : CanvasInputPolicy.name(requested.name()));
    store.saveIfVersion(updated, requested.expectedVersion());
    return updated;
  }
}
