package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Coordinates route selection, pricing and balance checks before provider submission. */
public final class PrepareMediaGenerationHandler {

  private static final int BILLING_SCALE = 4;

  private final MediaGenerationPreparationPort port;

  public PrepareMediaGenerationHandler(MediaGenerationPreparationPort port) {
    this.port = Objects.requireNonNull(port, "port");
  }

  public MediaGenerationPreparationResult handle(PrepareMediaGenerationCommand command) {
    PrepareMediaGenerationCommand requested = Objects.requireNonNull(command, "command");
    MediaGenerationIdentity identity = Objects.requireNonNull(requested.identity(), "identity");
    BigDecimal quantity = Objects.requireNonNull(requested.quantity(), "quantity");
    if (quantity.signum() <= 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }

    MediaChannelSelection channel = requested.selectedChannel();
    if (channel == null) {
      channel = port.selectChannel(requested.requestedModel());
    }
    if (channel == null) {
      return MediaGenerationPreparationResult.noChannel();
    }
    if (identity.pricingGroupId() == null) {
      return MediaGenerationPreparationResult.noPricingGroup();
    }

    MediaPricing pricing =
        port.findPricing(identity.pricingGroupId(), requested.requestedModel(), requested.resolution());
    if (pricing == null) {
      return MediaGenerationPreparationResult.noPricing();
    }

    BigDecimal cost =
        pricing.pricePerUnit().multiply(quantity).setScale(BILLING_SCALE, RoundingMode.DOWN);
    if (identity.balance().compareTo(cost) < 0) {
      return MediaGenerationPreparationResult.insufficient(cost, identity.balance());
    }
    return MediaGenerationPreparationResult.ready(channel, cost);
  }
}
