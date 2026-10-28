package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;
import java.time.Instant;

/** Application input for applying a normalized provider polling outcome. */
public record ApplyMediaProviderPollCommand(
    String jobId,
    MediaProviderPollResult result,
    VideoDeliveryPlan deliveryPlan,
    Instant observedAt) {}
