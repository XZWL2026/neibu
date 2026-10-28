package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import java.util.Objects;

/** Records provider acceptance while preserving idempotent task identity. */
public final class AcceptMediaJobHandler {

  private final MediaJobAcceptanceStore store;

  public AcceptMediaJobHandler(MediaJobAcceptanceStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public MediaJob handle(AcceptMediaJobCommand command) {
    AcceptMediaJobCommand requested = Objects.requireNonNull(command, "command");
    MediaJob job = store.load(requested.jobId());
    if (job.status() == MediaJobStatus.FAILED || job.status() == MediaJobStatus.COMPLETED) {
      return job;
    }
    if (job.accept(requested.providerTaskId(), requested.acceptedAt())) {
      store.save(job);
    }
    return job;
  }
}
