package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import java.util.Objects;

/** Persists a media generation intent before worker preparation begins. */
public final class SubmitMediaJobHandler {

  private final MediaJobSubmissionStore store;

  public SubmitMediaJobHandler(MediaJobSubmissionStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public MediaJob handle(SubmitMediaJobCommand command) {
    SubmitMediaJobCommand requested = Objects.requireNonNull(command, "command");
    MediaJob job =
        MediaJob.prepare(
            requested.jobId(),
            requested.mediaType(),
            requested.requestedModel(),
            requested.prompt(),
            requested.createdAt());
    store.save(job);
    return job;
  }
}
