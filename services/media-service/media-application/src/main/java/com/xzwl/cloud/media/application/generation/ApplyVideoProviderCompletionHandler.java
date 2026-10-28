package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.generation.VideoDeliveryMode;
import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;
import java.util.Objects;

/** Applies the native-versus-enhancement completion route to a persisted video job. */
public final class ApplyVideoProviderCompletionHandler {

  private final MediaJobCompletionStore store;

  public ApplyVideoProviderCompletionHandler(MediaJobCompletionStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public MediaJob handle(ApplyVideoProviderCompletionCommand command) {
    ApplyVideoProviderCompletionCommand requested = Objects.requireNonNull(command, "command");
    VideoDeliveryPlan plan = Objects.requireNonNull(requested.deliveryPlan(), "deliveryPlan");
    MediaJob job = store.load(requested.jobId());
    if (job.mediaType() != MediaType.VIDEO) {
      throw new IllegalArgumentException("only a video job can apply video completion");
    }
    if (job.status() == MediaJobStatus.FAILED
        || job.status() == MediaJobStatus.ENHANCEMENT_STORAGE_PENDING) {
      return job;
    }
    boolean changed;
    if (plan.mode() == VideoDeliveryMode.NATIVE) {
      if (job.status() == MediaJobStatus.COMPLETED
          && (requested.providerOutputUrl().equals(job.storageSourceUrl())
              || requested.providerOutputUrl().equals(job.outputUrl()))) {
        return job;
      }
      changed = job.queueStorage(requested.providerOutputUrl(), requested.routedAt());
    } else {
      if ((job.status() == MediaJobStatus.ENHANCEMENT_PENDING
              || job.status() == MediaJobStatus.COMPLETED)
          && requested.providerOutputUrl().equals(job.storageSourceUrl())) {
        return job;
      }
      changed = job.queueStorage(requested.providerOutputUrl(), requested.routedAt());
    }
    if (changed) {
      store.save(job);
    }
    return job;
  }
}
