package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.util.Objects;

/** Queues an image provider output for storage archival before final completion. */
public final class ApplyImageProviderCompletionHandler {

  private final MediaJobCompletionStore store;

  public ApplyImageProviderCompletionHandler(MediaJobCompletionStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public MediaJob handle(ApplyImageProviderCompletionCommand command) {
    ApplyImageProviderCompletionCommand requested = Objects.requireNonNull(command, "command");
    MediaJob job = store.load(requested.jobId());
    if (job.mediaType() != MediaType.IMAGE) {
      throw new IllegalArgumentException("only an image job can apply image completion");
    }
    if (job.status() == MediaJobStatus.FAILED) {
      return job;
    }
    if (job.status() == MediaJobStatus.COMPLETED
        && (requested.providerOutputUrl().equals(job.storageSourceUrl())
            || requested.providerOutputUrl().equals(job.outputUrl()))) {
      return job;
    }
    if (job.queueStorage(requested.providerOutputUrl(), requested.completedAt())) {
      store.save(job);
    }
    return job;
  }
}
