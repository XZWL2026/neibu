package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.VideoResolution;
import java.util.Objects;

/** Provider output routing decision for the video worker. */
public record VideoProviderCompletionResult(
    Status status,
    String providerOutputUrl,
    VideoResolution sourceResolution,
    VideoResolution targetResolution) {

  public VideoProviderCompletionResult {
    status = Objects.requireNonNull(status, "status");
    providerOutputUrl = requireNonBlank(providerOutputUrl, "providerOutputUrl");
    sourceResolution = Objects.requireNonNull(sourceResolution, "sourceResolution");
    targetResolution = Objects.requireNonNull(targetResolution, "targetResolution");
  }

  public enum Status {
    NATIVE_COMPLETED,
    ENHANCEMENT_REQUIRED
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
