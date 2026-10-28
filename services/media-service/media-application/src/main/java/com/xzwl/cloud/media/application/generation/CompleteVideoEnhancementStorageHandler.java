package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.util.Objects;

/** Finalizes a video enhancement only after the generated output is archived. */
public final class CompleteVideoEnhancementStorageHandler {

  private final MediaJobStorageStore store;

  public CompleteVideoEnhancementStorageHandler(MediaJobStorageStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public MediaJob handle(CompleteVideoEnhancementStorageCommand command) {
    CompleteVideoEnhancementStorageCommand requested = Objects.requireNonNull(command, "command");
    MediaJob job = store.load(requested.jobId());
    if (job.mediaType() != MediaType.VIDEO) {
      throw new IllegalArgumentException("only a video job can complete enhancement storage");
    }
    if (job.status() == MediaJobStatus.FAILED) {
      return job;
    }
    if (job.completeEnhancementStorage(requested.archivedOutputUrl(), requested.completedAt())) {
      store.save(job);
    }
    return job;
  }
}
