package com.xzwl.cloud.media.application.worker;

import java.time.Instant;
import java.util.Objects;

/** Scheduling decision for an asynchronous video provider poll. */
public record VideoProviderPollPlan(Status status, Instant nextPollAt, Instant deadlineAt) {

  public VideoProviderPollPlan {
    status = Objects.requireNonNull(status, "status");
    deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
    if (status == Status.WAIT && nextPollAt == null) {
      throw new IllegalArgumentException("waiting requires a next poll time");
    }
    if (status != Status.WAIT && nextPollAt != null) {
      throw new IllegalArgumentException("only waiting can carry a next poll time");
    }
  }

  public enum Status {
    WAIT,
    POLL_NOW,
    TIMED_OUT
  }

  static VideoProviderPollPlan waitUntil(Instant nextPollAt, Instant deadlineAt) {
    return new VideoProviderPollPlan(Status.WAIT, nextPollAt, deadlineAt);
  }

  static VideoProviderPollPlan pollNow(Instant deadlineAt) {
    return new VideoProviderPollPlan(Status.POLL_NOW, null, deadlineAt);
  }

  static VideoProviderPollPlan timedOut(Instant deadlineAt) {
    return new VideoProviderPollPlan(Status.TIMED_OUT, null, deadlineAt);
  }
}
