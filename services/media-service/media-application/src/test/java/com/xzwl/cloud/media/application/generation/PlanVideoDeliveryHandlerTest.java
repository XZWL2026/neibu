package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.VideoDeliveryMode;
import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;
import com.xzwl.cloud.media.domain.generation.VideoResolution;

public final class PlanVideoDeliveryHandlerTest {

  public static void main(String[] args) {
    plans480pAsNativeDelivery();
    plans720pAs480pThenEnhancement();
    plans1080pAs720pThenEnhancement();
    plans4kAs1080pThenEnhancement();
    System.out.println("PlanVideoDeliveryHandlerTest passed: 4");
  }

  private static void plans480pAsNativeDelivery() {
    VideoDeliveryPlan plan = plan(VideoResolution.R480P);

    assertEquals(VideoDeliveryMode.NATIVE, plan.mode(), "480p mode");
    assertEquals(VideoResolution.R480P, plan.sourceResolution(), "480p source");
    assertEquals(VideoResolution.R480P, plan.targetResolution(), "480p target");
  }

  private static void plans720pAs480pThenEnhancement() {
    VideoDeliveryPlan plan = plan(VideoResolution.R720P);

    assertEquals(VideoDeliveryMode.ENHANCEMENT, plan.mode(), "720p mode");
    assertEquals(VideoResolution.R480P, plan.sourceResolution(), "720p source");
    assertEquals(VideoResolution.R720P, plan.targetResolution(), "720p target");
  }

  private static void plans1080pAs720pThenEnhancement() {
    VideoDeliveryPlan plan = plan(VideoResolution.R1080P);

    assertEquals(VideoDeliveryMode.ENHANCEMENT, plan.mode(), "1080p mode");
    assertEquals(VideoResolution.R720P, plan.sourceResolution(), "1080p source");
    assertEquals(VideoResolution.R1080P, plan.targetResolution(), "1080p target");
  }

  private static void plans4kAs1080pThenEnhancement() {
    VideoDeliveryPlan plan = plan(VideoResolution.R4K);

    assertEquals(VideoDeliveryMode.ENHANCEMENT, plan.mode(), "4K mode");
    assertEquals(VideoResolution.R1080P, plan.sourceResolution(), "4K source");
    assertEquals(VideoResolution.R4K, plan.targetResolution(), "4K target");
  }

  private static VideoDeliveryPlan plan(VideoResolution resolution) {
    return new PlanVideoDeliveryHandler()
        .handle(new PlanVideoDeliveryCommand(resolution));
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }
}
