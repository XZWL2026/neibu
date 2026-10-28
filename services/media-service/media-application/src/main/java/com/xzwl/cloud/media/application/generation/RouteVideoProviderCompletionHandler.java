package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.VideoDeliveryMode;
import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;
import java.util.Objects;

/** Routes native output to completion and every enhanced target to post-processing. */
public final class RouteVideoProviderCompletionHandler {

  public VideoProviderCompletionResult handle(RouteVideoProviderCompletionCommand command) {
    RouteVideoProviderCompletionCommand requested = Objects.requireNonNull(command, "command");
    VideoDeliveryPlan plan = Objects.requireNonNull(requested.deliveryPlan(), "deliveryPlan");
    String outputUrl = requireNonBlank(requested.providerOutputUrl(), "providerOutputUrl");
    VideoProviderCompletionResult.Status status =
        plan.mode() == VideoDeliveryMode.NATIVE
            ? VideoProviderCompletionResult.Status.NATIVE_COMPLETED
            : VideoProviderCompletionResult.Status.ENHANCEMENT_REQUIRED;
    return new VideoProviderCompletionResult(
        status, outputUrl, plan.sourceResolution(), plan.targetResolution());
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
