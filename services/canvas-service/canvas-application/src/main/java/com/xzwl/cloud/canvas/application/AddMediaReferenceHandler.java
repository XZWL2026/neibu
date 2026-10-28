package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.Canvas;
import com.xzwl.cloud.canvas.domain.CanvasContent;
import java.util.ArrayList;
import java.util.Objects;

/** Adds a media result node while preserving the rest of the immutable canvas snapshot. */
public final class AddMediaReferenceHandler {

  private final CanvasStore store;

  public AddMediaReferenceHandler(CanvasStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public Canvas handle(AddMediaReferenceCommand command) {
    AddMediaReferenceCommand requested = Objects.requireNonNull(command, "command");
    Canvas current = new ReadCanvasHandler(store).handle(requested.canvasId());
    ArrayList<CanvasContent.Node> nodes = new ArrayList<>(current.content().nodes());
    nodes.add(Objects.requireNonNull(requested.media(), "media").toNode(requested.nodeId()));
    CanvasContent next = new CanvasContent(nodes, current.content().edges(), current.content().viewport());
    Canvas updated = current.save(next, requested.expectedVersion(), requested.savedAt());
    store.saveIfVersion(updated, requested.expectedVersion());
    return updated;
  }
}
