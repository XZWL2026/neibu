package com.xzwl.cloud.billing.domain.pricing;

/** Price selection dimensions. Values are owner-account type, model and requested resolution. */
public record PricingKey(String accountType, String model, String resolution) {

  public PricingKey {
    requireNonBlank(accountType, "accountType");
    requireNonBlank(model, "model");
    requireNonBlank(resolution, "resolution");
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
  }
}
