package com.xzwl.cloud.media.application.generation;

import java.util.Objects;

/** Provider-neutral route decision returned by the routing port. */
public record MediaChannelSelection(
    Long channelId, Long accountId, String requestedModel, String sourceModel) {

  public MediaChannelSelection {
    requestedModel = requireNonBlank(requestedModel, "requestedModel");
    sourceModel = requireNonBlank(sourceModel, "sourceModel");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
