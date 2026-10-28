package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.Canvas;
import com.xzwl.cloud.canvas.domain.CanvasContent;
import com.xzwl.cloud.canvas.domain.MediaResultReference;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class AddMediaReferenceHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-05T00:00:00Z");

  @Test
  void savesImageAndVideoResultsAsCanvasNodes() {
    InMemoryStore store = new InMemoryStore();
    store.save(Canvas.create("canvas-1", "owner-1", "分镜", CREATED_AT));
    AddMediaReferenceHandler handler = new AddMediaReferenceHandler(store);

    Canvas withImage = handler.handle(new AddMediaReferenceCommand(
        "canvas-1", 1, "image-node", new MediaResultReference(MediaResultReference.Kind.IMAGE, "img-public-1"),
        CREATED_AT.plusSeconds(1)));
    Canvas withVideo = handler.handle(new AddMediaReferenceCommand(
        "canvas-1", 2, "video-node", new MediaResultReference(MediaResultReference.Kind.VIDEO, "vid-public-1"),
        CREATED_AT.plusSeconds(2)));

    assertEquals(3, withVideo.version());
    assertEquals("img-public-1", withVideo.content().nodes().get(0).properties().get("publicId"));
    assertEquals("video", withVideo.content().nodes().get(1).kind());
    assertEquals("vid-public-1", withVideo.content().nodes().get(1).properties().get("publicId"));
  }

  @Test
  void rejectsEmptyMediaPublicId() {
    assertThrows(IllegalArgumentException.class,
        () -> new MediaResultReference(MediaResultReference.Kind.IMAGE, " "));
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
