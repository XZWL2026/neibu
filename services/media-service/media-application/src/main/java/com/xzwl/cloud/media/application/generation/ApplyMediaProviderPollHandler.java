package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.MediaJob;
import com.xzwl.cloud.media.domain.generation.MediaJobStatus;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.generation.VideoDeliveryMode;
import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;
import java.time.Instant;
import java.util.Objects;

/** Applies a provider-neutral polling outcome to the media job state machine. */
public final class ApplyMediaProviderPollHandler {

  private final MediaJobPollStore store;

  public ApplyMediaProviderPollHandler(MediaJobPollStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public MediaJob handle(ApplyMediaProviderPollCommand command) {
    ApplyMediaProviderPollCommand requested = Objects.requireNonNull(command, "command");
    MediaProviderPollResult result = Objects.requireNonNull(requested.result(), "result");
    MediaJob job = store.load(requested.jobId());
    switch (result.status()) {
      case PENDING -> {
        return job;
      }
      case FAILED -> {
        if (job.status() == MediaJobStatus.FAILED || job.status() == MediaJobStatus.COMPLETED) {
          return job;
        }
        job.fail(result.errorMessage(), observedAt(requested));
      }
      case COMPLETED -> {
        if (!applyCompletion(job, result.outputUrl(), requested)) {
          return job;
        }
      }
    }
    store.save(job);
    return job;
  }

  private static boolean applyCompletion(
      MediaJob job, String outputUrl, ApplyMediaProviderPollCommand command) {
    Instant observedAt = observedAt(command);
    if (job.status() == MediaJobStatus.FAILED
        || job.status() == MediaJobStatus.ENHANCEMENT_STORAGE_PENDING) {
      return false;
    }
    if (job.mediaType() == MediaType.IMAGE) {
      if (job.status() == MediaJobStatus.COMPLETED && outputUrl.equals(job.outputUrl())) {
        return false;
      }
      if (job.status() == MediaJobStatus.STORAGE_PENDING
          && outputUrl.equals(job.storageSourceUrl())) {
        return false;
      }
      job.queueStorage(outputUrl, observedAt);
      return true;
    }

    VideoDeliveryPlan plan = command.deliveryPlan();
    if (plan == null) {
      throw new IllegalArgumentException("deliveryPlan must not be null");
    }
    if (plan.mode() == VideoDeliveryMode.NATIVE) {
      if (job.status() == MediaJobStatus.COMPLETED
          && (outputUrl.equals(job.outputUrl()) || outputUrl.equals(job.storageSourceUrl()))) {
        return false;
      }
      if (job.status() == MediaJobStatus.STORAGE_PENDING
          && outputUrl.equals(job.storageSourceUrl())) {
        return false;
      }
      job.queueStorage(outputUrl, observedAt);
    } else {
      if ((job.status() == MediaJobStatus.ENHANCEMENT_PENDING
              || job.status() == MediaJobStatus.COMPLETED)
          && (outputUrl.equals(job.enhancementSourceUrl())
              || outputUrl.equals(job.storageSourceUrl()))) {
        return false;
      }
      if (job.status() == MediaJobStatus.STORAGE_PENDING
          && outputUrl.equals(job.storageSourceUrl())) {
        return false;
      }
      job.queueStorage(outputUrl, observedAt);
    }
    return true;
  }

  private static Instant observedAt(ApplyMediaProviderPollCommand command) {
    return Objects.requireNonNull(command.observedAt(), "observedAt");
  }
}
