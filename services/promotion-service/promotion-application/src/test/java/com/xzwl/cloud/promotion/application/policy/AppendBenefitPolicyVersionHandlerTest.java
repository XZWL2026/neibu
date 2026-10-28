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

public final class AppendBenefitPolicyVersionHandlerTest {

  private static final Instant T0 = Instant.parse("2026-09-05T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-09-05T01:00:00Z");

  public static void main(String[] args) {
    appendsAnEffectiveDatedOverrideWithoutRewritingEarlierRates();
    rejectsAStaleExpectedVersionWithoutSaving();
    System.out.println("AppendBenefitPolicyVersionHandlerTest passed: 2");
  }

  private static void appendsAnEffectiveDatedOverrideWithoutRewritingEarlierRates() {
    RecordingStore store = new RecordingStore(initialHistory());

    BenefitPolicyHistory updated =
        new AppendBenefitPolicyVersionHandler(store)
            .handle(
                new AppendBenefitPolicyVersionCommand(
                    "policy-1",
                    1,
                    T1,
                    Rate6.of("0.020000"),
                    Rate6.of("0.030000"),
                    Rate6.of("0.010000"),
                    Map.of(
                        "promoter-1",
                        new PromoterCashRateOverride(
                            "promoter-1", Rate6.of("0.050000"), Rate6.of("0.050000")))));

    check(updated.versions().size() == 2, "new policy version is appended");
    check(updated.effectiveAt(T1.minusNanos(1)).orElseThrow().cashRate("promoter-1", CashTier.LEVEL_1)
        .equals(Rate6.of("0.030000")), "earlier orders keep the default rate");
    check(updated.effectiveAt(T1).orElseThrow().cashRate("promoter-1", CashTier.LEVEL_1)
        .equals(Rate6.of("0.050000")), "later orders use the promoter override");
    check(store.calls.equals(List.of("load", "save")), "load and save order");
    check(store.saved == updated, "updated policy history is saved");
  }

  private static void rejectsAStaleExpectedVersionWithoutSaving() {
    RecordingStore store = new RecordingStore(initialHistory());

    expectThrows(
        IllegalStateException.class,
        () ->
            new AppendBenefitPolicyVersionHandler(store)
                .handle(
                    new AppendBenefitPolicyVersionCommand(
                        "policy-1",
                        2,
                        T1,
                        Rate6.of("0.020000"),
                        Rate6.of("0.050000"),
                        Rate6.of("0.010000"),
                        Map.of())));

    check(store.calls.equals(List.of("load")), "stale version is rejected before save");
    check(store.saved == null, "stale version is not saved");
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
                Map.of())));
  }

  private static void check(boolean condition, String label) {
    if (!condition) {
      throw new AssertionError(label);
    }
  }

  private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
    try {
      action.run();
    } catch (Throwable thrown) {
      if (type.isInstance(thrown)) {
        return type.cast(thrown);
      }
      throw new AssertionError("unexpected exception type", thrown);
    }
    throw new AssertionError("expected exception " + type.getSimpleName());
  }

  private static final class RecordingStore implements BenefitPolicyStore {

    private final List<String> calls = new ArrayList<>();
    private final BenefitPolicyHistory history;
    private BenefitPolicyHistory saved;

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
      saved = history;
    }
  }
}
