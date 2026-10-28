package com.xzwl.cloud.media.application.worker;

import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Duration;
import java.time.Instant;

public final class MediaExecutionDeadlinePolicyTest {

  private static final Instant STARTED_AT = Instant.parse("2026-09-04T07:00:00Z");

  public static void main(String[] args) {
    givesAsyncImageOneHour();
    givesSyncImageTwoMinutes();
    givesAsyncVideoSixHours();
    rejectsSyncVideo();
    rejectsMissingInputs();
    System.out.println("MediaExecutionDeadlinePolicyTest passed: 5");
  }

  private static void givesAsyncImageOneHour() {
    MediaExecutionDeadlinePolicy policy = new MediaExecutionDeadlinePolicy();
    assertEquals(Duration.ofHours(1), policy.timeoutFor(MediaType.IMAGE, MediaExecutionMode.ASYNC), "async image timeout");
    assertEquals(Instant.parse("2026-09-04T08:00:00Z"),
        policy.deadlineAt(MediaType.IMAGE, MediaExecutionMode.ASYNC, STARTED_AT), "async image deadline");
  }

  private static void givesSyncImageTwoMinutes() {
    MediaExecutionDeadlinePolicy policy = new MediaExecutionDeadlinePolicy();
    assertEquals(Duration.ofMinutes(2), policy.timeoutFor(MediaType.IMAGE, MediaExecutionMode.SYNC), "sync image timeout");
  }

  private static void givesAsyncVideoSixHours() {
    MediaExecutionDeadlinePolicy policy = new MediaExecutionDeadlinePolicy();
    assertEquals(Duration.ofHours(6), policy.timeoutFor(MediaType.VIDEO, MediaExecutionMode.ASYNC), "async video timeout");
    assertEquals(Instant.parse("2026-09-04T13:00:00Z"),
        policy.deadlineAt(MediaType.VIDEO, MediaExecutionMode.ASYNC, STARTED_AT), "async video deadline");
  }

  private static void rejectsSyncVideo() {
    try {
      new MediaExecutionDeadlinePolicy().timeoutFor(MediaType.VIDEO, MediaExecutionMode.SYNC);
      throw new AssertionError("sync video should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static void rejectsMissingInputs() {
    MediaExecutionDeadlinePolicy policy = new MediaExecutionDeadlinePolicy();
    try {
      policy.timeoutFor(null, MediaExecutionMode.ASYNC);
      throw new AssertionError("missing media type should be rejected");
    } catch (NullPointerException expected) {
      // expected
    }
    try {
      policy.deadlineAt(MediaType.IMAGE, MediaExecutionMode.ASYNC, null);
      throw new AssertionError("missing start time should be rejected");
    } catch (NullPointerException expected) {
      // expected
    }
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }
}
