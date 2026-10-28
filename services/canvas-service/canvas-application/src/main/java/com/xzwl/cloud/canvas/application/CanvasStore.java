package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.Canvas;
import java.util.List;
import java.util.Optional;

/** Persistence port for canvas aggregates. */
public interface CanvasStore {

  /** Returns the latest complete snapshot, when the canvas exists. */
  default Optional<Canvas> findById(String canvasId) {
    return Optional.empty();
  }

  default List<Canvas> findPage(int offset, int limit) {
    return List.of();
  }

  void save(Canvas canvas);

  /**
   * Stores a new snapshot after checking the version read by the caller.
   *
   * <p>Adapters with an atomic conditional write should override this method. The default keeps
   * the creation-only port source-compatible while the predev application remains in-memory.
   */
  default void saveIfVersion(Canvas canvas, long expectedVersion) {
    save(canvas);
  }
}
