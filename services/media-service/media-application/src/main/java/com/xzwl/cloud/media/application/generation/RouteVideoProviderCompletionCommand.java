package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;

/** Application input for routing a completed provider video output. */
public record RouteVideoProviderCompletionCommand(
    VideoDeliveryPlan deliveryPlan,
    String providerOutputUrl) {}
