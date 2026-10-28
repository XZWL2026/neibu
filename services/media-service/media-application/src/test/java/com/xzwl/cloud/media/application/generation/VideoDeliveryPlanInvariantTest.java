package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.VideoDeliveryMode;
import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;
import com.xzwl.cloud.media.domain.generation.VideoResolution;

public final class VideoDeliveryPlanInvariantTest {

  public static void main(String[] args) {
    rejectsNative720p();
    rejectsEnhancement480p();
    rejectsWrongEnhancementSource();
    acceptsOnlyCanonicalPlans();
    System.out.println("VideoDeliveryPlanInvariantTest passed: 4");
  }

  private static void rejectsNative720p() {
    expectIllegalArgument(
        () -> new VideoDeliveryPlan(
            VideoResolution.R720P,
            VideoResolution.R720P,
            VideoDeliveryMode.NATIVE),
        "720p native plan");
  }

  private static void rejectsEnhancement480p() {
    expectIllegalArgument(
        () -> new VideoDeliveryPlan(
            VideoResolution.R480P,
            VideoResolution.R480P,
            VideoDeliveryMode.ENHANCEMENT),
        "480p enhancement plan");
  }

  private static void rejectsWrongEnhancementSource() {
    expectIllegalArgument(
        () -> new VideoDeliveryPlan(
            VideoResolution.R480P,
            VideoResolution.R1080P,
            VideoDeliveryMode.ENHANCEMENT),
        "1080p wrong source");
  }

  private static void acceptsOnlyCanonicalPlans() {
    VideoDeliveryPlan plan = new VideoDeliveryPlan(
        VideoResolution.R720P,
        VideoResolution.R1080P,
        VideoDeliveryMode.ENHANCEMENT);

    if (plan.mode() != VideoDeliveryMode.ENHANCEMENT
        || plan.sourceResolution() != VideoResolution.R720P
        || plan.targetResolution() != VideoResolution.R1080P) {
      throw new AssertionError("canonical enhancement plan was rejected or changed");
    }
  }

  private static void expectIllegalArgument(Runnable action, String label) {
    try {
      action.run();
      throw new AssertionError(label + " should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }
}
