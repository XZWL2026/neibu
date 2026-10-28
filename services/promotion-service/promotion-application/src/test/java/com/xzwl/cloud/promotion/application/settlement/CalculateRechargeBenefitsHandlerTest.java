package com.xzwl.cloud.promotion.application.settlement;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.money.Rate6;
import com.xzwl.cloud.promotion.domain.policy.BenefitPolicyHistory;
import com.xzwl.cloud.promotion.domain.policy.BenefitPolicyVersion;
import com.xzwl.cloud.promotion.domain.referral.BenefitSnapshot;
import com.xzwl.cloud.promotion.domain.settlement.RechargeBenefitResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CalculateRechargeBenefitsHandlerTest {

  public static void main(String[] args) {
    usesPolicyEffectiveAtRechargeCreationAndFreezesThatRate();
    System.out.println("CalculateRechargeBenefitsHandlerTest passed: 1");
  }

  private static void usesPolicyEffectiveAtRechargeCreationAndFreezesThatRate() {
    BenefitPolicyHistory history = BenefitPolicyHistory.rehydrate(List.of(
        new BenefitPolicyVersion("policy-1", 1, Instant.parse("2026-09-05T00:00:00Z"),
            Rate6.ZERO, Rate6.of("0.03"), Rate6.of("0.03"), Map.of()),
        new BenefitPolicyVersion("policy-1", 2, Instant.parse("2026-09-05T01:00:00Z"),
            Rate6.ZERO, Rate6.of("0.03"), Rate6.of("0.03"), Map.of(
                "promoter-1", new com.xzwl.cloud.promotion.domain.policy.PromoterCashRateOverride(
                    "promoter-1", Rate6.of("0.05"), Rate6.of("0.05"))))));

    RechargeBenefitResult oldOrder = new CalculateRechargeBenefitsHandler(history).handle(
        RechargeBenefitCommand.cashOnly("old-event", "old-order", "old-lot", "user-1",
            Instant.parse("2026-09-05T00:30:00Z"), 1L, "promoter-1", Decimal4.of("100")));
    RechargeBenefitResult newOrder = new CalculateRechargeBenefitsHandler(history).handle(
        RechargeBenefitCommand.cashOnly("new-event", "new-order", "new-lot", "user-1",
            Instant.parse("2026-09-05T01:30:00Z"), 2L, "promoter-1", Decimal4.of("100")));

    check(oldOrder.fundingBenefitSnapshot().cashRate6().equals(Rate6.of("0.03")),
        "old order keeps 3 percent");
    check(oldOrder.fundingBenefitSnapshot().frozenCommission4().equals(Decimal4.of("3.0000")),
        "old commission remains frozen");
    check(newOrder.fundingBenefitSnapshot().cashRate6().equals(Rate6.of("0.05")),
        "new order uses 5 percent");
    check(newOrder.fundingBenefitSnapshot().frozenCommission4().equals(Decimal4.of("5.0000")),
        "new commission freezes at 5 percent");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
