package com.xzwl.cloud.billing.application.pricing;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.pricing.PricingCatalog;
import com.xzwl.cloud.billing.domain.pricing.PricingKey;
import com.xzwl.cloud.billing.domain.pricing.PricingEntry;
import java.util.Optional;

public final class SetPricingHandlerTest {

  public static void main(String[] args) {
    adminReplacesPriceWithVersionProtection();
    priceIsUnavailableWhenAbsentAndZeroMeansFree();
    System.out.println("SetPricingHandlerTest passed: 2");
  }

  private static void adminReplacesPriceWithVersionProtection() {
    RecordingStore store = new RecordingStore();
    SetPricingHandler handler = new SetPricingHandler(store);
    PricingKey key = new PricingKey("PERSONAL", "image-model", "1K");
    PricingCatalog next = handler.handle(new SetPricingCommand(
        1, new PricingEntry(key, Decimal4.of("0.12349"))));

    check(next.version() == 2, "catalog version increments");
    check(next.priceFor(key).orElseThrow().equals(Decimal4.of("0.1234")), "price truncates to four decimals");
    check(next.quote(key, Decimal4.of("3")).equals(Decimal4.of("0.3702")), "image quantity is priced");
    expectThrows(IllegalStateException.class, () -> handler.handle(
        new SetPricingCommand(1, new PricingEntry(key, Decimal4.of("0.2")))));
  }

  private static void priceIsUnavailableWhenAbsentAndZeroMeansFree() {
    PricingKey key = new PricingKey("ENTERPRISE", "video-model", "480p");
    PricingCatalog catalog = PricingCatalog.empty().withPrice(new PricingEntry(key, Decimal4.zero()));

    check(catalog.priceFor(new PricingKey("PERSONAL", "video-model", "480p")).isEmpty(), "different account is unavailable");
    check(catalog.priceFor(key).orElseThrow().equals(Decimal4.zero()), "zero price is available for free");
    check(catalog.quote(key, Decimal4.of("12.5")).equals(Decimal4.zero()), "free quote remains zero");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }

  private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
    try {
      action.run();
    } catch (Throwable thrown) {
      if (type.isInstance(thrown)) return type.cast(thrown);
      throw new AssertionError("unexpected exception type", thrown);
    }
    throw new AssertionError("expected exception " + type.getSimpleName());
  }

  private static final class RecordingStore implements PricingPolicyStore {
    private PricingCatalog catalog = PricingCatalog.empty();

    @Override
    public PricingCatalog load() {
      return catalog;
    }

    @Override
    public void save(PricingCatalog updated) {
      catalog = updated;
    }
  }
}
