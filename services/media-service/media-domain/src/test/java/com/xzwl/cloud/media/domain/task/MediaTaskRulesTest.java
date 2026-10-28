package com.xzwl.cloud.media.domain.task;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaTaskRulesTest {
  @Test void portraitReferenceHasExplicitOwnerBoundReviewIdentifier() {
    assertTrue(java.util.Arrays.stream(MediaReference.class.getRecordComponents()).anyMatch(c -> c.getName().equals("portraitId")), "portrait references need a separate review identifier");
  }
  @Test void acceptsOnlyFileIdsAndHttpsReferences() {
    assertEquals("file-1", new MediaReference("file-1", null).fileId());
    assertNotNull(new MediaReference(null, "https://cdn.example/image.png"));
    for (String url : List.of("data:image/png;base64,AAAA", "AAAA", "http://cdn.example/a",
        "https://user:pass@cdn.example/a", "file:///tmp/a")) {
      assertThrows(IllegalArgumentException.class, () -> new MediaReference(null, url));
    }
    assertThrows(IllegalArgumentException.class, () -> new MediaReference("data:AAAA", null));
    assertThrows(IllegalArgumentException.class, () -> new MediaReference("file-1", "https://cdn.example/a"));
  }

  @Test void validatesImageAndVideoQuantities() {
    assertNotNull(new TaskRequest(MediaType.IMAGE, "model", "prompt", "1024", 3, 0, List.of()));
    assertNotNull(new TaskRequest(MediaType.VIDEO, "model", "prompt", "720p", 1, 5, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new TaskRequest(MediaType.VIDEO, "model", "prompt", "720p", 3, 0, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new TaskRequest(MediaType.IMAGE, "model", "data:image/png;base64,AAAA", "1024", 1, 0, List.of()));
  }

  @Test void normalizesMissingSelectionsAndRejectsUnsupportedQuantitiesAndRatios() {
    var image = new TaskRequest(MediaType.IMAGE, null, "prompt", null, null, 0, 0, null);
    assertEquals("seedream-5.0-lite", image.model());
    assertEquals("1:1", image.aspectRatio());
    assertEquals("2K", image.resolution());
    assertEquals(1, image.count());
    assertEquals(0, image.durationSeconds());
    var video = new TaskRequest(MediaType.VIDEO, null, "prompt", null, null, 0, 0, null);
    assertEquals("seedance-2.5", video.model());
    assertEquals("16:9", video.aspectRatio());
    assertEquals("720p", video.resolution());
    assertEquals(1, video.count());
    assertEquals(5, video.durationSeconds());
    assertNotNull(new TaskRequest(MediaType.IMAGE, "model", "prompt", "4:3", "2K", 4, 0, null));
    assertEquals("21:9", new TaskRequest(MediaType.IMAGE, "model", "prompt", "21:9", "2K", 1, 0, null).aspectRatio());
    assertEquals("21:9", new TaskRequest(MediaType.VIDEO, "model", "prompt", "21:9", "720p", 1, 5, null).aspectRatio());
    assertThrows(IllegalArgumentException.class,
        () -> new TaskRequest(MediaType.IMAGE, "model", "prompt", "1:1", "2K", 5, 0, null));
    assertThrows(IllegalArgumentException.class,
        () -> new TaskRequest(MediaType.IMAGE, "model", "prompt", "7:2", "2K", 1, 0, null));
    assertThrows(IllegalArgumentException.class,
        () -> new TaskRequest(MediaType.VIDEO, "model", "prompt", "16:9", "720p", 2, 5, null));
    assertThrows(IllegalArgumentException.class,
        () -> new TaskRequest(MediaType.IMAGE, "model", "prompt", "1:1", "2K", -1, 0, null));
  }

  @Test void cannotSkipChargingOrMutateTerminalState() {
    assertTrue(TaskStatus.ROUTING.canTransitionTo(TaskStatus.CHARGE_PENDING));
    assertFalse(TaskStatus.ROUTING.canTransitionTo(TaskStatus.RUNNING));
    assertFalse(TaskStatus.SUCCEEDED.canTransitionTo(TaskStatus.REFUND_PENDING));
    assertTrue(TaskStatus.REFUND_PENDING.canTransitionTo(TaskStatus.TIMED_OUT));
  }

  @Test void refundUsesOriginalPointsAndFullFailureReturnsTheExactDebit() {
    ChargeReceipt receipt = new ChargeReceipt("charge-1", new BigDecimal("12.3456"),
        new BigDecimal("10.0000"), new BigDecimal("123.4560"));
    assertEquals(new BigDecimal("123.4560"), receipt.refundPoints(3, 3));
    assertEquals(new BigDecimal("41.1520"), receipt.refundPoints(1, 3));
    assertThrows(IllegalArgumentException.class, () -> receipt.refundPoints(4, 3));
  }
}
