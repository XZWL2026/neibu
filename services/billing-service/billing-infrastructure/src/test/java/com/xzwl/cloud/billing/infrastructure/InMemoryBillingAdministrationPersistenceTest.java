package com.xzwl.cloud.billing.infrastructure;

import com.xzwl.cloud.billing.application.credit.AdminCreditCommand;
import com.xzwl.cloud.billing.application.credit.AdminCreditHandler;
import com.xzwl.cloud.billing.application.pricing.SetPricingCommand;
import com.xzwl.cloud.billing.application.pricing.SetPricingHandler;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.pricing.PricingEntry;
import com.xzwl.cloud.billing.domain.pricing.PricingKey;
import com.xzwl.cloud.billing.domain.wallet.WalletBalance;
import java.time.Instant;

public final class InMemoryBillingAdministrationPersistenceTest {

  public static void main(String[] args) {
    adminCreditAndPricingSurviveAcrossHandlerInstances();
    System.out.println("InMemoryBillingAdministrationPersistenceTest passed: 1");
  }

  private static void adminCreditAndPricingSurviveAcrossHandlerInstances() {
    InMemoryBillingAdministrationPersistence persistence =
        new InMemoryBillingAdministrationPersistence();
    persistence.openWallet(WalletBalance.zero("wallet-1"));

    AdminCreditCommand credit =
        new AdminCreditCommand(
            "request-1",
            "wallet-1",
            "key-1",
            Decimal4.of("20.0000"),
            "admin-1",
            Instant.parse("2026-09-07T00:00:00Z"),
            "manual credit");
    new AdminCreditHandler(persistence).handle(credit);
    var replay = new AdminCreditHandler(persistence).handle(credit);

    PricingKey pricingKey = new PricingKey("PERSONAL", "image-model", "1K");
    new SetPricingHandler(persistence)
        .handle(
            new SetPricingCommand(
                1, new PricingEntry(pricingKey, Decimal4.of("2.5000"))));

    check(replay.balance().availablePoints4().equals(Decimal4.of("20.0000")),
        "credit replay reads persisted balance and record");
    check(persistence.load().quote(pricingKey, Decimal4.of("2.0000"))
        .equals(Decimal4.of("5.0000")), "pricing catalog persists configured price");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
