package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.util.Objects;

/** Queues an enhancement output for storage archival before final completion. */
public final class CompleteVideoEnhancementHandler {

  private final MediaJobEnhancementStore store;

  public CompleteVideoEnhancementHandler(MediaJobEnhancementStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public MediaJob handle(CompleteVideoEnhancementCommand command) {
    CompleteVideoEnhancementCommand requested = Objects.requireNonNull(command, "command");
    MediaJob job = store.load(requested.jobId());
    if (job.mediaType() != MediaType.VIDEO) {
      throw new IllegalArgumentException("only a video job can complete enhancement");
    }
    if (job.status() == MediaJobStatus.FAILED || job.status() == MediaJobStatus.COMPLETED) {
      return job;
    }
    if (job.queueEnhancementOutputStorage(requested.finalOutputUrl(), requested.completedAt())) {
      store.save(job);
    }
    return job;
  }
}
