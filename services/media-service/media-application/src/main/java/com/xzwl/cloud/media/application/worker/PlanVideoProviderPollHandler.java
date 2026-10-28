package com.xzwl.cloud.media.application.worker;

import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Enforces the fixed five-second video poll cadence and six-hour deadline. */
public final class PlanVideoProviderPollHandler {

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

  private final MediaExecutionDeadlinePolicy deadlinePolicy;

  public PlanVideoProviderPollHandler(MediaExecutionDeadlinePolicy deadlinePolicy) {
    this.deadlinePolicy = Objects.requireNonNull(deadlinePolicy, "deadlinePolicy");
  }

  public VideoProviderPollPlan handle(PlanVideoProviderPollCommand command) {
    PlanVideoProviderPollCommand requested = Objects.requireNonNull(command, "command");
    Instant startedAt = Objects.requireNonNull(requested.startedAt(), "startedAt");
    Instant now = Objects.requireNonNull(requested.now(), "now");
    if (now.isBefore(startedAt)) {
      throw new IllegalArgumentException("now must not be before startedAt");
    }

    Instant lastPolledAt = requested.lastPolledAt();
    if (lastPolledAt != null
        && (lastPolledAt.isBefore(startedAt) || lastPolledAt.isAfter(now))) {
      throw new IllegalArgumentException("lastPolledAt must be between startedAt and now");
    }

    Instant deadlineAt = deadlinePolicy.deadlineAt(
        MediaType.VIDEO, MediaExecutionMode.ASYNC, startedAt);
    if (!now.isBefore(deadlineAt)) {
      return VideoProviderPollPlan.timedOut(deadlineAt);
    }
    if (lastPolledAt == null) {
      return VideoProviderPollPlan.pollNow(deadlineAt);
    }

    Instant nextPollAt = lastPolledAt.plus(POLL_INTERVAL);
    return now.isBefore(nextPollAt)
        ? VideoProviderPollPlan.waitUntil(nextPollAt, deadlineAt)
        : VideoProviderPollPlan.pollNow(deadlineAt);
  }
}
