package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;

/** Outcome of the provider-neutral media generation preparation step. */
public record MediaGenerationPreparationResult(
    Status status,
    MediaChannelSelection channel,
    BigDecimal cost,
    BigDecimal required,
    BigDecimal balance) {

  public enum Status {
    NO_CHANNEL,
    NO_PRICING_GROUP,
    NO_PRICING,
    INSUFFICIENT_BALANCE,
    READY
  }

  static MediaGenerationPreparationResult noChannel() {
    return new MediaGenerationPreparationResult(Status.NO_CHANNEL, null, null, null, null);
  }

  static MediaGenerationPreparationResult noPricingGroup() {
    return new MediaGenerationPreparationResult(Status.NO_PRICING_GROUP, null, null, null, null);
  }

  static MediaGenerationPreparationResult noPricing() {
    return new MediaGenerationPreparationResult(Status.NO_PRICING, null, null, null, null);
  }

  static MediaGenerationPreparationResult insufficient(
      BigDecimal required, BigDecimal balance) {
    return new MediaGenerationPreparationResult(
        Status.INSUFFICIENT_BALANCE, null, null, required, balance);
  }

  static MediaGenerationPreparationResult ready(MediaChannelSelection channel, BigDecimal cost) {
    return new MediaGenerationPreparationResult(Status.READY, channel, cost, null, null);
  }
}
