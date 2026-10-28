package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.Canvas;
import com.xzwl.cloud.canvas.domain.CanvasContent;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class SaveCanvasHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-05T00:00:00Z");
  private static final Instant UPDATED_AT = Instant.parse("2026-09-05T00:01:00Z");

  @Test
  void readsFullSnapshotAndSavesWithCompareAndSwapVersion() {
    InMemoryStore store = new InMemoryStore();
    Canvas original = Canvas.create("canvas-1", "owner-1", "分镜", CREATED_AT);
    store.save(original);
    CanvasContent content = new CanvasContent(
        java.util.List.of(new CanvasContent.Node("n1", "text", Map.of("value", "hello"))),
        java.util.List.of(),
        new CanvasContent.Viewport(10, 20, 1.5));

    assertEquals(original, new ReadCanvasHandler(store).handle("canvas-1"));

    Canvas saved = new SaveCanvasHandler(store).handle(
        new SaveCanvasCommand("canvas-1", 1, content, UPDATED_AT));

    assertEquals(2, saved.version());
    assertEquals(content, saved.content());
    assertEquals(saved, new ReadCanvasHandler(store).handle("canvas-1"));
  }

  @Test
  void rejectsStaleVersionWithoutOverwritingCurrentSnapshot() {
    InMemoryStore store = new InMemoryStore();
    Canvas original = Canvas.create("canvas-1", "owner-1", "分镜", CREATED_AT);
    store.save(original);
    CanvasContent first = new CanvasContent(
        java.util.List.of(new CanvasContent.Node("n1", "text", Map.of("value", "first"))),
        java.util.List.of(),
        new CanvasContent.Viewport(0, 0, 1));
    CanvasContent stale = new CanvasContent(
        java.util.List.of(new CanvasContent.Node("n1", "text", Map.of("value", "stale"))),
        java.util.List.of(),
        new CanvasContent.Viewport(0, 0, 1));

    new SaveCanvasHandler(store).handle(new SaveCanvasCommand("canvas-1", 1, first, UPDATED_AT));

    assertThrows(
        IllegalStateException.class,
        () -> new SaveCanvasHandler(store).handle(
            new SaveCanvasCommand("canvas-1", 1, stale, UPDATED_AT.plusSeconds(1))));
    assertEquals(first, new ReadCanvasHandler(store).handle("canvas-1").content());
    assertEquals(2, new ReadCanvasHandler(store).handle("canvas-1").version());
  }

  @Test void savesNameAndContentInOneVersionAndRejectsInvalidOrStaleNames() {
    InMemoryStore store = new InMemoryStore();
    Canvas original = Canvas.create("canvas-1", "owner-1", "分镜", CREATED_AT);
    store.save(original);
    var handler = new SaveCanvasHandler(store);
    var renamed = handler.handle(new SaveCanvasCommand("canvas-1", 1, CanvasContent.empty(), UPDATED_AT, "新分镜"));
    assertEquals("新分镜", renamed.name());
    assertEquals(2, renamed.version());
    for (String invalid : java.util.List.of(" ", "x".repeat(201))) {
      assertThrows(IllegalArgumentException.class, () -> handler.handle(new SaveCanvasCommand("canvas-1", 2, CanvasContent.empty(), UPDATED_AT, invalid)));
    }
    assertThrows(IllegalStateException.class, () -> handler.handle(new SaveCanvasCommand("canvas-1", 1, CanvasContent.empty(), UPDATED_AT, "旧版本")));
    assertEquals(renamed, store.findById("canvas-1").orElseThrow());
    assertEquals("新分镜", handler.handle(new SaveCanvasCommand("canvas-1", 2, CanvasContent.empty(), UPDATED_AT)).name());
  }

  private static final class InMemoryStore implements CanvasStore {

    private final Map<String, Canvas> canvases = new HashMap<>();

    @Override
    public Optional<Canvas> findById(String canvasId) {
      return Optional.ofNullable(canvases.get(canvasId));
    }

    @Override
    public void save(Canvas canvas) {
      canvases.put(canvas.id(), canvas);
    }

    @Override
    public void saveIfVersion(Canvas canvas, long expectedVersion) {
      Canvas current = canvases.get(canvas.id());
      if (current == null || current.version() != expectedVersion) {
        throw new IllegalStateException("expectedVersion does not match stored canvas");
      }
      canvases.put(canvas.id(), canvas);
    }
  }
}
