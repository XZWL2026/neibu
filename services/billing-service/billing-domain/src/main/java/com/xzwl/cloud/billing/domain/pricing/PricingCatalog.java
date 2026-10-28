package com.xzwl.cloud.billing.domain.pricing;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, versioned pricing catalog selected by owner account type/model/resolution. */
public final class PricingCatalog {

  private final long version;
  private final Map<PricingKey, Decimal4> prices;

  private PricingCatalog(long version, Map<PricingKey, Decimal4> prices) {
    if (version < 1) throw new IllegalArgumentException("version must be positive");
    this.version = version;
    this.prices = Map.copyOf(prices);
  }

  public static PricingCatalog empty() {
    return new PricingCatalog(1, Map.of());
  }

  public long version() {
    return version;
  }

  public Optional<Decimal4> priceFor(PricingKey key) {
    return Optional.ofNullable(prices.get(Objects.requireNonNull(key, "key")));
  }

  public Decimal4 quote(PricingKey key, Decimal4 quantity) {
    Decimal4 units = Objects.requireNonNull(quantity, "quantity");
    if (!units.isPositive()) throw new IllegalArgumentException("quantity must be positive");
    return priceFor(key).orElseThrow(() -> new IllegalArgumentException("price is unavailable")).multiply(units);
  }

  public java.util.List<PricingEntry> entriesFor(String accountType) {
    return prices.entrySet().stream().filter(entry -> entry.getKey().accountType().equals(accountType))
        .sorted(java.util.Comparator.comparing((Map.Entry<PricingKey, Decimal4> entry) -> entry.getKey().model())
            .thenComparing(entry -> entry.getKey().resolution()))
        .map(entry -> new PricingEntry(entry.getKey(), entry.getValue())).toList();
  }

  public PricingCatalog withPrice(PricingEntry entry) {
    PricingEntry requested = Objects.requireNonNull(entry, "entry");
    Map<PricingKey, Decimal4> updated = new HashMap<>(prices);
    updated.put(requested.key(), requested.pricePerUnit());
    return new PricingCatalog(version + 1, updated);
  }
}
