package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.VideoResolution;

/** Application input for selecting the fixed video delivery path. */
public record PlanVideoDeliveryCommand(VideoResolution targetResolution) {}
