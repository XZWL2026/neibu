package com.xzwl.cloud.billing.domain.pricing;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;

/** A configured price per image or per video second. */
public record PricingEntry(PricingKey key, Decimal4 pricePerUnit) {

  public PricingEntry {
    key = Objects.requireNonNull(key, "key");
    pricePerUnit = Objects.requireNonNull(pricePerUnit, "pricePerUnit");
    if (pricePerUnit.isNegative()) throw new IllegalArgumentException("pricePerUnit must not be negative");
  }
}
