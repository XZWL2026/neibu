package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;
import java.util.Objects;

/** Read-only identity and balance snapshot used while preparing a media job. */
public record MediaGenerationIdentity(Long pricingGroupId, BigDecimal balance) {

  public MediaGenerationIdentity {
    balance = Objects.requireNonNull(balance, "balance");
  }
}
