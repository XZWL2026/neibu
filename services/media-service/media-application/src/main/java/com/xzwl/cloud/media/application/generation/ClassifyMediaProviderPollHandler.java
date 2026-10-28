package com.xzwl.cloud.media.application.generation;

import java.util.Locale;
import java.util.Objects;

/** Normalizes provider statuses before dispatching completion or failure handling. */
public final class ClassifyMediaProviderPollHandler {

  public MediaProviderPollResult handle(ClassifyMediaProviderPollCommand command) {
    ClassifyMediaProviderPollCommand requested = Objects.requireNonNull(command, "command");
    String status = requested.providerStatus();
    String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "completed", "succeeded" -> MediaProviderPollResult.completed(requested.outputUrl());
      case "failed", "error" -> MediaProviderPollResult.failed(requested.errorMessage());
      default -> MediaProviderPollResult.pending();
    };
  }
}
