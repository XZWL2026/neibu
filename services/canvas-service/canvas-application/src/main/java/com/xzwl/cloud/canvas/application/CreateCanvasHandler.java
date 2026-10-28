package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.Canvas;
import java.util.Objects;

/** Coordinates canvas creation without coupling the use case to an adapter. */
public final class CreateCanvasHandler {

  private final CanvasStore store;

  public CreateCanvasHandler(CanvasStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public Canvas handle(CreateCanvasCommand command) {
    CreateCanvasCommand requested = Objects.requireNonNull(command, "command");
    Canvas canvas =
        Canvas.create(
            requested.canvasId(), requested.ownerId(), requested.name(), requested.createdAt());
    store.save(canvas);
    return canvas;
  }
}
