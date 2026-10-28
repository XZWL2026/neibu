package com.xzwl.cloud.billing.application.pricing;

import com.xzwl.cloud.billing.domain.pricing.PricingCatalog;

/** Persistence port for the administrator-managed pricing catalog. */
public interface PricingPolicyStore {

  PricingCatalog load();

  void save(PricingCatalog catalog);
}
