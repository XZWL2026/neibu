package com.xzwl.cloud.media.application.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Fixed retry budget and capped exponential backoff for ordinary media work. */
public final class MediaWorkerRetryPolicy {

  private static final int MAX_ATTEMPTS = 5;
  private static final long INITIAL_BACKOFF_SECONDS = 5;
  private static final long MAX_BACKOFF_SECONDS = Duration.ofMinutes(5).getSeconds();

  public boolean canRetry(int attemptCount) {
    validateAttemptCount(attemptCount);
    return attemptCount < MAX_ATTEMPTS;
  }

  public Instant nextRetryAt(int attemptCount, Instant failedAt) {
    validateAttemptCount(attemptCount);
    Instant validatedFailedAt = Objects.requireNonNull(failedAt, "failedAt");
    int exponent = Math.min(attemptCount - 1, 6);
    long backoffSeconds = Math.min(INITIAL_BACKOFF_SECONDS << exponent, MAX_BACKOFF_SECONDS);
    return validatedFailedAt.plusSeconds(backoffSeconds);
  }

  private static void validateAttemptCount(int attemptCount) {
    if (attemptCount <= 0) {
      throw new IllegalArgumentException("attemptCount must be positive");
    }
  }
}
