package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.Canvas;
import java.util.Objects;

/** Marks a canvas deleted while retaining its last snapshot for the persistence adapter. */
public final class DeleteCanvasHandler {

  private final CanvasStore store;

  public DeleteCanvasHandler(CanvasStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public Canvas handle(DeleteCanvasCommand command) {
    DeleteCanvasCommand requested = Objects.requireNonNull(command, "command");
    Canvas current = new ReadCanvasHandler(store).handle(requested.canvasId());
    Canvas deleted = current.delete(requested.expectedVersion(), requested.deletedAt());
    store.saveIfVersion(deleted, requested.expectedVersion());
    return deleted;
  }
}
