package com.xzwl.cloud.media.application.generation;

import java.util.Objects;

/** Provider-neutral polling outcome consumed by media completion handlers. */
public record MediaProviderPollResult(Status status, String outputUrl, String errorMessage) {

  public MediaProviderPollResult {
    status = Objects.requireNonNull(status, "status");
    switch (status) {
      case PENDING -> {
        if (outputUrl != null || errorMessage != null) {
          throw new IllegalArgumentException("pending result cannot carry output or error");
        }
      }
      case COMPLETED -> {
        outputUrl = requireNonBlank(outputUrl, "outputUrl");
        if (errorMessage != null) {
          throw new IllegalArgumentException("completed result cannot carry an error");
        }
      }
      case FAILED -> {
        errorMessage = requireNonBlank(errorMessage, "errorMessage");
        if (outputUrl != null) {
          throw new IllegalArgumentException("failed result cannot carry output");
        }
      }
    }
  }

  public enum Status {
    PENDING,
    COMPLETED,
    FAILED
  }

  static MediaProviderPollResult pending() {
    return new MediaProviderPollResult(Status.PENDING, null, null);
  }

  static MediaProviderPollResult completed(String outputUrl) {
    return new MediaProviderPollResult(Status.COMPLETED, outputUrl, null);
  }

  static MediaProviderPollResult failed(String errorMessage) {
    return new MediaProviderPollResult(Status.FAILED, null, errorMessage);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
