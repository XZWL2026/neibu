package com.xzwl.cloud.media.domain.generation;

import java.util.Objects;

/** Fixed source and post-processing plan for one public video resolution. */
public record VideoDeliveryPlan(
    VideoResolution sourceResolution,
    VideoResolution targetResolution,
    VideoDeliveryMode mode) {

  public VideoDeliveryPlan {
    sourceResolution = Objects.requireNonNull(sourceResolution, "sourceResolution");
    targetResolution = Objects.requireNonNull(targetResolution, "targetResolution");
    mode = Objects.requireNonNull(mode, "mode");
    VideoResolution expectedSource = null;
    VideoDeliveryMode expectedMode = null;
    switch (targetResolution) {
      case R480P -> {
        expectedSource = VideoResolution.R480P;
        expectedMode = VideoDeliveryMode.NATIVE;
      }
      case R720P -> {
        expectedSource = VideoResolution.R480P;
        expectedMode = VideoDeliveryMode.ENHANCEMENT;
      }
      case R1080P -> {
        expectedSource = VideoResolution.R720P;
        expectedMode = VideoDeliveryMode.ENHANCEMENT;
      }
      case R4K -> {
        expectedSource = VideoResolution.R1080P;
        expectedMode = VideoDeliveryMode.ENHANCEMENT;
      }
    }
    if (sourceResolution != expectedSource || mode != expectedMode) {
      throw new IllegalArgumentException("video delivery plan does not match fixed resolution rule");
    }
  }

  public static VideoDeliveryPlan forTarget(VideoResolution targetResolution) {
    VideoResolution target = Objects.requireNonNull(targetResolution, "targetResolution");
    return switch (target) {
      case R480P ->
          new VideoDeliveryPlan(VideoResolution.R480P, VideoResolution.R480P, VideoDeliveryMode.NATIVE);
      case R720P ->
          new VideoDeliveryPlan(VideoResolution.R480P, VideoResolution.R720P, VideoDeliveryMode.ENHANCEMENT);
      case R1080P ->
          new VideoDeliveryPlan(VideoResolution.R720P, VideoResolution.R1080P, VideoDeliveryMode.ENHANCEMENT);
      case R4K ->
          new VideoDeliveryPlan(VideoResolution.R1080P, VideoResolution.R4K, VideoDeliveryMode.ENHANCEMENT);
    };
  }
}
