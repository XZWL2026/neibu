package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import java.util.Objects;

/** Records provider or enhancement failure without hiding the failed job state. */
public final class FailMediaJobHandler {

  private final MediaJobFailureStore store;

  public FailMediaJobHandler(MediaJobFailureStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public MediaJob handle(FailMediaJobCommand command) {
    FailMediaJobCommand requested = Objects.requireNonNull(command, "command");
    MediaJob job = store.load(requested.jobId());
    if (job.status() == MediaJobStatus.FAILED || job.status() == MediaJobStatus.COMPLETED) {
      return job;
    }
    if (job.fail(requested.failureMessage(), requested.failedAt())) {
      store.save(job);
    }
    return job;
  }
}
