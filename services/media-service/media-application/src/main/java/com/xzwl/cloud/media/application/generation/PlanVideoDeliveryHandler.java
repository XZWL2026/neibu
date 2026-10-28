package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;
import java.util.Objects;

/** Applies the fixed native-versus-enhancement rule for video delivery. */
public final class PlanVideoDeliveryHandler {

  public VideoDeliveryPlan handle(PlanVideoDeliveryCommand command) {
    PlanVideoDeliveryCommand requested = Objects.requireNonNull(command, "command");
    return VideoDeliveryPlan.forTarget(requested.targetResolution());
  }
}
