package com.xzwl.cloud.billing.application.pricing;

import com.xzwl.cloud.billing.domain.pricing.PricingEntry;

/** Internal administrator command for one pricing dimension. */
public record SetPricingCommand(long expectedVersion, PricingEntry entry) {}
