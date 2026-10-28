package com.xzwl.cloud.promotion.application.policy;

import com.xzwl.cloud.promotion.domain.money.Rate6;
import com.xzwl.cloud.promotion.domain.policy.BenefitPolicyHistory;
import com.xzwl.cloud.promotion.domain.policy.BenefitPolicyVersion;
import com.xzwl.cloud.promotion.domain.policy.PromoterCashRateOverride;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class UpdatePromoterCashRateOverrideHandlerTest {

  private static final Instant T0 = Instant.parse("2026-09-05T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-09-05T01:00:00Z");

  public static void main(String[] args) {
    updatesOnlyTheRequestedPromoterAndPreservesDefaultsAndOtherOverrides();
    System.out.println("UpdatePromoterCashRateOverrideHandlerTest passed: 1");
  }

  private static void updatesOnlyTheRequestedPromoterAndPreservesDefaultsAndOtherOverrides() {
    RecordingStore store = new RecordingStore(initialHistory());

    BenefitPolicyHistory updated =
        new UpdatePromoterCashRateOverrideHandler(store)
            .handle(
                new UpdatePromoterCashRateOverrideCommand(
                    "policy-1",
                    1,
                    T1,
                    "promoter-a",
                    Rate6.of("0.050000"),
                    Rate6.of("0.040000")));

    BenefitPolicyVersion version = updated.effectiveAt(T1).orElseThrow();
    check(version.cashRate("promoter-a", CashTier.LEVEL_1).equals(Rate6.of("0.050000")),
        "requested promoter level one override is applied");
    check(version.cashRate("promoter-a", CashTier.LEVEL_2).equals(Rate6.of("0.040000")),
        "requested promoter level two override is applied");
    check(version.cashRate("promoter-b", CashTier.LEVEL_1).equals(Rate6.of("0.060000")),
        "other promoter override is preserved");
    check(version.cashRate("promoter-c", CashTier.LEVEL_1).equals(Rate6.of("0.030000")),
        "global default remains unchanged");
    check(updated.effectiveAt(T1.minusNanos(1)).orElseThrow()
            .cashRate("promoter-a", CashTier.LEVEL_1).equals(Rate6.of("0.030000")),
        "earlier policy remains unchanged");
    check(store.calls.equals(List.of("load", "save")), "load and save order");
  }

  private static BenefitPolicyHistory initialHistory() {
    return BenefitPolicyHistory.rehydrate(
        List.of(
            new BenefitPolicyVersion(
                "policy-1",
                1,
                T0,
                Rate6.of("0.020000"),
                Rate6.of("0.030000"),
                Rate6.of("0.010000"),
                Map.of(
                    "promoter-b",
                    new PromoterCashRateOverride(
                        "promoter-b", Rate6.of("0.060000"), Rate6.of("0.050000"))))));
  }

  private static void check(boolean condition, String label) {
    if (!condition) {
      throw new AssertionError(label);
    }
  }

  private static final class RecordingStore implements BenefitPolicyStore {

    private final List<String> calls = new ArrayList<>();
    private final BenefitPolicyHistory history;

    private RecordingStore(BenefitPolicyHistory history) {
      this.history = history;
    }

    @Override
    public BenefitPolicyHistory load(String historyId) {
      calls.add("load");
      check(history.historyId().equals(historyId), "policy history id is loaded");
      return history;
    }

    @Override
    public void save(BenefitPolicyHistory history) {
      calls.add("save");
    }
  }
}
