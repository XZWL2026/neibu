package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import java.util.Objects;

/** Finalizes a provider output only after storage archival succeeds. */
public final class CompleteMediaStorageHandler {

  private final MediaJobStorageStore store;

  public CompleteMediaStorageHandler(MediaJobStorageStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public MediaJob handle(CompleteMediaStorageCommand command) {
    CompleteMediaStorageCommand requested = Objects.requireNonNull(command, "command");
    MediaJob job = store.load(requested.jobId());
    if (job.status() == MediaJobStatus.FAILED) {
      return job;
    }
    if (job.completeStorage(requested.archivedOutputUrl(), requested.completedAt())) {
      store.save(job);
    }
    return job;
  }
}
