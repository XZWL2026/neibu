package com.xzwl.cloud.promotion.infrastructure;

import com.xzwl.cloud.promotion.application.commission.FreezeCommissionCommand;
import com.xzwl.cloud.promotion.application.commission.FreezeCommissionHandler;
import com.xzwl.cloud.promotion.application.commission.ReleaseCommissionCommand;
import com.xzwl.cloud.promotion.application.commission.ReleaseCommissionHandler;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.time.Instant;

public final class InMemoryCommissionPersistenceTest {

  public static void main(String[] args) {
    freezeAndReleaseRoundTripUsesOnePersistedAccount();
    System.out.println("InMemoryCommissionPersistenceTest passed: 1");
  }

  private static void freezeAndReleaseRoundTripUsesOnePersistedAccount() {
    InMemoryCommissionPersistence persistence = new InMemoryCommissionPersistence();
    FreezeCommissionHandler freezer = new FreezeCommissionHandler(persistence);
    ReleaseCommissionHandler releaser = new ReleaseCommissionHandler(persistence);
    Instant at = Instant.parse("2026-09-07T00:00:00Z");

    freezer.handle(
        new FreezeCommissionCommand(
            "promoter-1", "event-1", "order-1", "lot-1", Decimal4.of("3.0000"), 1,
            CashTier.LEVEL_1, at));
    releaser.handle(
        new ReleaseCommissionCommand(
            "promoter-1", "event-2", "order-1", "lot-1", "allocation-1",
            Decimal4.of("3.0000"), at.plusSeconds(1)));

    check(persistence.load("promoter-1").entries().size() == 2,
        "freeze and release are stored on one account");
    check(persistence.load("promoter-1").currentSnapshot().available()
        .equals(Decimal4.of("3.0000")), "released commission is available");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
