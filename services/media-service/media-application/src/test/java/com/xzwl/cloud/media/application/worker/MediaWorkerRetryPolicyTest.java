package com.xzwl.cloud.media.application.worker;

import java.time.Duration;
import java.time.Instant;

public final class MediaWorkerRetryPolicyTest {

  private static final Instant FAILED_AT = Instant.parse("2026-09-04T07:00:00Z");

  public static void main(String[] args) {
    allowsOnlyAttemptsBelowFive();
    appliesExponentialBackoff();
    capsBackoffAtFiveMinutes();
    rejectsInvalidAttemptCount();
    System.out.println("MediaWorkerRetryPolicyTest passed: 4");
  }

  private static void allowsOnlyAttemptsBelowFive() {
    MediaWorkerRetryPolicy policy = new MediaWorkerRetryPolicy();
    assertEquals(true, policy.canRetry(1), "attempt one retry");
    assertEquals(true, policy.canRetry(4), "attempt four retry");
    assertEquals(false, policy.canRetry(5), "attempt five retry");
  }

  private static void appliesExponentialBackoff() {
    MediaWorkerRetryPolicy policy = new MediaWorkerRetryPolicy();
    assertEquals(Instant.parse("2026-09-04T07:00:05Z"), policy.nextRetryAt(1, FAILED_AT), "attempt one delay");
    assertEquals(Instant.parse("2026-09-04T07:00:10Z"), policy.nextRetryAt(2, FAILED_AT), "attempt two delay");
    assertEquals(Instant.parse("2026-09-04T07:00:20Z"), policy.nextRetryAt(3, FAILED_AT), "attempt three delay");
  }

  private static void capsBackoffAtFiveMinutes() {
    MediaWorkerRetryPolicy policy = new MediaWorkerRetryPolicy();
    Instant expected = FAILED_AT.plus(Duration.ofMinutes(5));
    assertEquals(expected, policy.nextRetryAt(7, FAILED_AT), "capped delay");
  }

  private static void rejectsInvalidAttemptCount() {
    MediaWorkerRetryPolicy policy = new MediaWorkerRetryPolicy();
    try {
      policy.canRetry(0);
      throw new AssertionError("zero attempt count should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
    try {
      policy.nextRetryAt(0, FAILED_AT);
      throw new AssertionError("zero attempt count should be rejected for scheduling");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }
}
