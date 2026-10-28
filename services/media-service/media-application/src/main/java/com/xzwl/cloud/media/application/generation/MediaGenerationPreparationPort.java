package com.xzwl.cloud.media.application.generation;

/** Consumer-side ports for routing and pricing lookups. */
public interface MediaGenerationPreparationPort {

  MediaChannelSelection selectChannel(String requestedModel);

  MediaPricing findPricing(Long pricingGroupId, String requestedModel, String resolution);
}
