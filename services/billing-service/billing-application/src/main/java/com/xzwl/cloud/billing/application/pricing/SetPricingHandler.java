package com.xzwl.cloud.billing.application.pricing;

import com.xzwl.cloud.billing.domain.pricing.PricingCatalog;
import java.util.Objects;

/** Version-protected administrator pricing update. */
public final class SetPricingHandler {

  private final PricingPolicyStore store;

  public SetPricingHandler(PricingPolicyStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public PricingCatalog handle(SetPricingCommand command) {
    SetPricingCommand requested = Objects.requireNonNull(command, "command");
    PricingCatalog current = Objects.requireNonNull(store.load(), "catalog");
    if (current.version() != requested.expectedVersion()) {
      throw new IllegalStateException("expectedVersion does not match pricing catalog version");
    }
    PricingCatalog updated = current.withPrice(requested.entry());
    store.save(updated);
    return updated;
  }
}
