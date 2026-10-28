package com.xzwl.cloud.media.application.worker;

import com.xzwl.cloud.media.application.generation.MediaJobPollStore;
import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import java.time.Instant;
import java.util.Objects;

/** Moves an active provider operation to terminal failure once its deadline is reached. */
public final class ExpireMediaJobHandler {

  private static final String TIMEOUT_MESSAGE = "media provider timed out";

  private final MediaJobPollStore store;
  private final MediaExecutionDeadlinePolicy deadlinePolicy;

  public ExpireMediaJobHandler(
      MediaJobPollStore store, MediaExecutionDeadlinePolicy deadlinePolicy) {
    this.store = Objects.requireNonNull(store, "store");
    this.deadlinePolicy = Objects.requireNonNull(deadlinePolicy, "deadlinePolicy");
  }

  public MediaJob handle(ExpireMediaJobCommand command) {
    ExpireMediaJobCommand requested = Objects.requireNonNull(command, "command");
    Instant startedAt = Objects.requireNonNull(requested.startedAt(), "startedAt");
    Instant now = Objects.requireNonNull(requested.now(), "now");
    if (now.isBefore(startedAt)) {
      throw new IllegalArgumentException("now must not be before startedAt");
    }

    MediaJob job = store.load(requested.jobId());
    Instant deadlineAt = deadlinePolicy.deadlineAt(job.mediaType(), requested.mode(), startedAt);
    if (now.isBefore(deadlineAt)
        || (job.status() != MediaJobStatus.PROVIDER_ACCEPTED
            && job.status() != MediaJobStatus.STORAGE_PENDING
            && job.status() != MediaJobStatus.ENHANCEMENT_PENDING
            && job.status() != MediaJobStatus.ENHANCEMENT_STORAGE_PENDING)) {
      return job;
    }

    job.fail(TIMEOUT_MESSAGE, now);
    store.save(job);
    return job;
  }
}
