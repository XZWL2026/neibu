package com.xzwl.cloud.canvas.application;

import com.xzwl.cloud.canvas.domain.Canvas;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class CreateCanvasHandlerTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-05T00:00:00Z");

  @Test
  void createsVersionOneEmptyCanvasAndSavesIt() {
    RecordingStore store = new RecordingStore();

    Canvas result =
        new CreateCanvasHandler(store)
            .handle(new CreateCanvasCommand("canvas-1", "owner-1", "雨夜分镜", CREATED_AT));

    assertEquals("canvas-1", result.id());
    assertEquals("owner-1", result.ownerId());
    assertEquals("雨夜分镜", result.name());
    assertEquals(1L, result.version());
    assertTrue(result.content().isEmpty());
    assertEquals(List.of(result), store.saved);
  }

  private static final class RecordingStore implements CanvasStore {

    private final List<Canvas> saved = new ArrayList<>();

    @Override
    public void save(Canvas canvas) {
      saved.add(canvas);
    }
  }
}
