package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.Canvas;
import com.xzwl.cloud.canvas.domain.CanvasContent;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class CopyDeleteCanvasHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-05T00:00:00Z");
  private static final Instant CHANGED_AT = Instant.parse("2026-09-05T00:01:00Z");

  @Test
  void copiesContentIntoIndependentVersionOneCanvas() {
    InMemoryStore store = new InMemoryStore();
    Canvas source = Canvas.create("canvas-1", "owner-1", "原稿", CREATED_AT);
    CanvasContent content = new CanvasContent(
        java.util.List.of(new CanvasContent.Node("n1", "image", Map.of("publicId", "img-1"))),
        java.util.List.of(),
        new CanvasContent.Viewport(0, 0, 1));
    source = source.save(content, 1, CHANGED_AT);
    store.save(source);

    Canvas copy = new CopyCanvasHandler(store).handle(
        new CopyCanvasCommand("canvas-1", "canvas-2", "副本", CHANGED_AT.plusSeconds(1)));

    assertEquals("canvas-2", copy.id());
    assertEquals("owner-1", copy.ownerId());
    assertEquals("副本", copy.name());
    assertEquals(1, copy.version());
    assertEquals(content, copy.content());
    assertNotEquals(source.id(), copy.id());
    assertEquals(copy, new ReadCanvasHandler(store).handle("canvas-2"));
  }

  @Test
  void deleteMakesCanvasUnreadableAndUnsavable() {
    InMemoryStore store = new InMemoryStore();
    store.save(Canvas.create("canvas-1", "owner-1", "原稿", CREATED_AT));

    Canvas deleted = new DeleteCanvasHandler(store).handle(
        new DeleteCanvasCommand("canvas-1", 1, CHANGED_AT));

    assertEquals(2, deleted.version());
    assertThrows(IllegalArgumentException.class,
        () -> new ReadCanvasHandler(store).handle("canvas-1"));
    assertThrows(IllegalArgumentException.class,
        () -> new SaveCanvasHandler(store).handle(
            new SaveCanvasCommand("canvas-1", 2, CanvasContent.empty(), CHANGED_AT.plusSeconds(1))));
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
