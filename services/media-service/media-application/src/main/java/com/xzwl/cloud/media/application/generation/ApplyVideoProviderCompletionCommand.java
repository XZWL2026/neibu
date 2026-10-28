package com.xzwl.cloud.media.application.generation;

import com.xzwl.cloud.media.domain.generation.VideoDeliveryPlan;
import java.time.Instant;

/** Application input for applying a completed provider video output. */
public record ApplyVideoProviderCompletionCommand(
    String jobId,
    VideoDeliveryPlan deliveryPlan,
    String providerOutputUrl,
    Instant routedAt) {}
