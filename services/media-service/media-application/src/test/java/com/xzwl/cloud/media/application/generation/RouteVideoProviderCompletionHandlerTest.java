package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;
import com.xzwl.cloud.media.domain.generation.VideoResolution;

public final class RouteVideoProviderCompletionHandlerTest {

  public static void main(String[] args) {
    routes480pToNativeCompletion();
    routes1080pToEnhancement();
    rejectsMissingProviderOutput();
    System.out.println("RouteVideoProviderCompletionHandlerTest passed: 3");
  }

  private static void routes480pToNativeCompletion() {
    VideoProviderCompletionResult result = route(VideoResolution.R480P, "tos://video-480");

    assertEquals(
        VideoProviderCompletionResult.Status.NATIVE_COMPLETED,
        result.status(),
        "480p status");
    assertEquals("tos://video-480", result.providerOutputUrl(), "480p output");
    assertEquals(VideoResolution.R480P, result.sourceResolution(), "480p source");
    assertEquals(VideoResolution.R480P, result.targetResolution(), "480p target");
  }

  private static void routes1080pToEnhancement() {
    VideoProviderCompletionResult result = route(VideoResolution.R1080P, "tos://video-720");

    assertEquals(
        VideoProviderCompletionResult.Status.ENHANCEMENT_REQUIRED,
        result.status(),
        "1080p status");
    assertEquals("tos://video-720", result.providerOutputUrl(), "1080p output");
    assertEquals(VideoResolution.R720P, result.sourceResolution(), "1080p source");
    assertEquals(VideoResolution.R1080P, result.targetResolution(), "1080p target");
  }

  private static void rejectsMissingProviderOutput() {
    VideoDeliveryPlan plan = new PlanVideoDeliveryHandler().handle(
        new PlanVideoDeliveryCommand(VideoResolution.R480P));
    try {
      new RouteVideoProviderCompletionHandler().handle(
          new RouteVideoProviderCompletionCommand(plan, " "));
      throw new AssertionError("missing provider output should be rejected");
    } catch (IllegalArgumentException expected) {
      // expected
    }
  }

  private static VideoProviderCompletionResult route(
      VideoResolution targetResolution,
      String providerOutputUrl) {
    VideoDeliveryPlan plan = new PlanVideoDeliveryHandler().handle(
        new PlanVideoDeliveryCommand(targetResolution));
    return new RouteVideoProviderCompletionHandler().handle(
        new RouteVideoProviderCompletionCommand(plan, providerOutputUrl));
  }

  private static void assertEquals(Object expected, Object actual, String label) {
    if (!expected.equals(actual)) {
      throw new AssertionError(label + " expected " + expected + " but was " + actual);
    }
  }
}
