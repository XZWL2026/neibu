package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.util.Objects;

/** Starts enhancement only after a video provider source is archived. */
public final class CompleteVideoSourceStorageHandler {

  private final MediaJobStorageStore store;

  public CompleteVideoSourceStorageHandler(MediaJobStorageStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public MediaJob handle(CompleteVideoSourceStorageCommand command) {
    CompleteVideoSourceStorageCommand requested = Objects.requireNonNull(command, "command");
    MediaJob job = store.load(requested.jobId());
    if (job.mediaType() != MediaType.VIDEO) {
      throw new IllegalArgumentException("only a video job can archive an enhancement source");
    }
    if (job.status() == MediaJobStatus.FAILED
        || job.status() == MediaJobStatus.ENHANCEMENT_STORAGE_PENDING) {
      return job;
    }
    if (job.queueEnhancementFromStorage(requested.archivedSourceUrl(), requested.queuedAt())) {
      store.save(job);
    }
    return job;
  }
}
