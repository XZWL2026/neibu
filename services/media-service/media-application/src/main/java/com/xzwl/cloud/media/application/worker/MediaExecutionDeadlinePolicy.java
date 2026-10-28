package com.xzwl.cloud.media.application.worker;

import com.xzwl.cloud.media.domain.generation.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Fixed maximum execution windows for image and video generation. */
public final class MediaExecutionDeadlinePolicy {

  private static final Duration ASYNC_IMAGE_TIMEOUT = Duration.ofHours(1);
  private static final Duration SYNC_IMAGE_TIMEOUT = Duration.ofMinutes(2);
  private static final Duration ASYNC_VIDEO_TIMEOUT = Duration.ofHours(6);

  public Duration timeoutFor(MediaType mediaType, MediaExecutionMode mode) {
    MediaType validatedMediaType = Objects.requireNonNull(mediaType, "mediaType");
    MediaExecutionMode validatedMode = Objects.requireNonNull(mode, "mode");
    if (validatedMediaType == MediaType.IMAGE) {
      return validatedMode == MediaExecutionMode.ASYNC
          ? ASYNC_IMAGE_TIMEOUT
          : SYNC_IMAGE_TIMEOUT;
    }
    if (validatedMode != MediaExecutionMode.ASYNC) {
      throw new IllegalArgumentException("video generation must be asynchronous");
    }
    return ASYNC_VIDEO_TIMEOUT;
  }

  public Instant deadlineAt(
      MediaType mediaType, MediaExecutionMode mode, Instant startedAt) {
    Instant validatedStartedAt = Objects.requireNonNull(startedAt, "startedAt");
    return validatedStartedAt.plus(timeoutFor(mediaType, mode));
  }
}
