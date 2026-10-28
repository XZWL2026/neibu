package com.xzwl.cloud.media.application.generation;

import java.math.BigDecimal;
import java.util.Objects;

/** Price snapshot returned by the billing catalog for one media unit. */
public record MediaPricing(BigDecimal pricePerUnit) {

  public MediaPricing {
    pricePerUnit = Objects.requireNonNull(pricePerUnit, "pricePerUnit");
    if (pricePerUnit.signum() < 0) {
      throw new IllegalArgumentException("pricePerUnit must not be negative");
    }
  }
}
