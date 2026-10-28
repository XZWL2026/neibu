package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import java.util.Objects;

/** Preserves an ambiguous provider outcome so the worker cannot blindly resubmit. */
public final class MarkMediaJobSubmissionUncertainHandler {

  private final MediaJobSubmissionUncertainStore store;

  public MarkMediaJobSubmissionUncertainHandler(MediaJobSubmissionUncertainStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public MediaJob handle(MarkMediaJobSubmissionUncertainCommand command) {
    MarkMediaJobSubmissionUncertainCommand requested =
        Objects.requireNonNull(command, "command");
    MediaJob job = store.load(requested.jobId());
    if (job.status() == MediaJobStatus.FAILED || job.status() == MediaJobStatus.COMPLETED) {
      return job;
    }
    if (job.markSubmissionUncertain(requested.uncertainAt())) {
      store.save(job);
    }
    return job;
  }
}
