package com.xzwl.cloud.billing.domain.funding;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.lang.reflect.Executable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public final class FundingLotFifoPlannerTest {

  private static int passed;

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() throws Exception { main(new String[0]); }

  public static void main(String[] args) {
    decimal4TruncatesTowardZeroAndHasStableValueSemantics();
    decimal4PublicApiRejectsFloatingPointTypes();
    fundingLotProtectsBalancesAndRewardEligibility();
    fifoConsumesTheRequiredExampleWithoutMutatingInputs();
    fifoUsesLotIdAsTheStableTieBreaker();
    plannerRejectsInvalidRequestsBeforeProducingAPlan();
    planAndAllocationsAreImmutableAndExact();
    System.out.println("FundingLotFifoPlannerTest passed: " + passed);
  }

  private static void decimal4TruncatesTowardZeroAndHasStableValueSemantics() {
    Decimal4 positive = Decimal4.of("12.34569");
    Decimal4 negative = Decimal4.of(new BigDecimal("-12.34569"));

    check(positive.asBigDecimal().equals(new BigDecimal("12.3456")), "positive truncation");
    check(negative.asBigDecimal().equals(new BigDecimal("-12.3456")), "negative truncation");
    check(Decimal4.of("1.2").equals(Decimal4.of("1.20009")), "fixed-scale equality");
    check(Decimal4.of("1.2").hashCode() == Decimal4.of("1.20009").hashCode(), "stable hash");
    check(Decimal4.of("1.0000").compareTo(Decimal4.of("0.99999")) > 0, "numeric comparison");
    passed++;
  }

  private static void decimal4PublicApiRejectsFloatingPointTypes() {
    Stream<Executable> api =
        Stream.concat(
            Arrays.stream(Decimal4.class.getConstructors()),
            Arrays.stream(Decimal4.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())));
    check(
        api.flatMap(executable -> Arrays.stream(executable.getParameterTypes()))
            .noneMatch(
                type ->
                    type == double.class
                        || type == Double.class
                        || type == float.class
                        || type == Float.class),
        "floating point API is forbidden");
    passed++;
  }

  private static void fundingLotProtectsBalancesAndRewardEligibility() {
    Instant creditedAt = Instant.parse("2026-09-04T00:00:00Z");
    FundingLot real = lot("real-1", "wallet-1", FundingLotType.REAL_RECHARGE, "10", "4", creditedAt, true);

    check(real.id().equals("real-1"), "lot id");
    check(real.originalPoints4().equals(Decimal4.of("10")), "original amount");
    check(real.remainingPoints4().equals(Decimal4.of("4")), "remaining amount");
    expectThrows(
        IllegalArgumentException.class,
        () -> lot("gift-1", "wallet-1", FundingLotType.GIFT, "10", "10", creditedAt, true));
    expectThrows(
        IllegalArgumentException.class,
        () -> lot("reward-1", "wallet-1", FundingLotType.REFERRAL_REWARD, "10", "10", creditedAt, true));
    expectThrows(
        IllegalArgumentException.class,
        () -> lot("negative", "wallet-1", FundingLotType.REAL_RECHARGE, "10", "-1", creditedAt, true));
    expectThrows(
        IllegalArgumentException.class,
        () -> lot("too-much", "wallet-1", FundingLotType.REAL_RECHARGE, "10", "11", creditedAt, true));
    passed++;
  }

  private static void fifoConsumesTheRequiredExampleWithoutMutatingInputs() {
    List<FundingLot> input =
        List.of(
            lot("gift", "wallet-1", FundingLotType.GIFT, "30", "30", at(1), false),
            lot("real-l1", "wallet-1", FundingLotType.REAL_RECHARGE, "100", "100", at(2), true),
            lot("real-l2", "wallet-1", FundingLotType.REAL_RECHARGE, "100", "100", at(3), true),
            lot("reward", "wallet-1", FundingLotType.REFERRAL_REWARD, "20", "20", at(4), false));

    FundingConsumptionPlan plan =
        FundingLotFifoPlanner.plan("operation-1", "wallet-1", Decimal4.of("180"), input);

    check(plan.allocations().size() == 3, "three allocations");
    assertAllocation(plan.allocations().get(0), "operation-1", "gift", "wallet-1", "30", FundingLotType.GIFT, false);
    assertAllocation(plan.allocations().get(1), "operation-1", "real-l1", "wallet-1", "100", FundingLotType.REAL_RECHARGE, true);
    assertAllocation(plan.allocations().get(2), "operation-1", "real-l2", "wallet-1", "50", FundingLotType.REAL_RECHARGE, true);
    check(remaining(plan, "gift").equals(Decimal4.zero()), "gift exhausted");
    check(remaining(plan, "real-l1").equals(Decimal4.zero()), "first recharge exhausted");
    check(remaining(plan, "real-l2").equals(Decimal4.of("50")), "second recharge remainder");
    check(remaining(plan, "reward").equals(Decimal4.of("20")), "reward untouched");
    check(input.get(0).remainingPoints4().equals(Decimal4.of("30")), "input remains unchanged");
    check(input.get(2).remainingPoints4().equals(Decimal4.of("100")), "input recharge unchanged");
    passed++;
  }

  private static void fifoUsesLotIdAsTheStableTieBreaker() {
    Instant sameTime = at(1);
    List<FundingLot> input =
        List.of(
            lot("lot-b", "wallet-1", FundingLotType.REAL_RECHARGE, "10", "10", sameTime, true),
            lot("lot-a", "wallet-1", FundingLotType.GIFT, "10", "10", sameTime, false));

    FundingConsumptionPlan plan =
        FundingLotFifoPlanner.plan("operation-2", "wallet-1", Decimal4.of("12"), input);

    check(plan.allocations().get(0).lotId().equals("lot-a"), "id tie-breaker first lot");
    check(plan.allocations().get(1).lotId().equals("lot-b"), "id tie-breaker second lot");
    check(plan.allocations().get(1).allocatedPoints4().equals(Decimal4.of("2")), "tie remainder");
    passed++;
  }

  private static void plannerRejectsInvalidRequestsBeforeProducingAPlan() {
    FundingLot wallet1 = lot("lot-1", "wallet-1", FundingLotType.REAL_RECHARGE, "10", "10", at(1), true);
    FundingLot wallet2 = lot("lot-2", "wallet-2", FundingLotType.REAL_RECHARGE, "10", "10", at(2), true);
    List<FundingLot> insufficient = new ArrayList<>(List.of(wallet1));

    expectThrows(
        IllegalArgumentException.class,
        () -> FundingLotFifoPlanner.plan("operation-3", "wallet-1", Decimal4.of("11"), insufficient));
    check(insufficient.get(0).remainingPoints4().equals(Decimal4.of("10")), "insufficient input unchanged");
    expectThrows(
        IllegalArgumentException.class,
        () -> FundingLotFifoPlanner.plan("operation-3", "wallet-1", Decimal4.zero(), List.of(wallet1)));
    expectThrows(
        IllegalArgumentException.class,
        () -> FundingLotFifoPlanner.plan("operation-3", "wallet-1", Decimal4.of("-1"), List.of(wallet1)));
    expectThrows(
        IllegalArgumentException.class,
        () -> FundingLotFifoPlanner.plan("operation-3", "wallet-1", Decimal4.of("1"), List.of(wallet1, wallet2)));
    expectThrows(
        IllegalArgumentException.class,
        () -> FundingLotFifoPlanner.plan("operation-3", "wallet-1", Decimal4.of("1"), List.of(wallet1, wallet1)));
    passed++;
  }

  private static void planAndAllocationsAreImmutableAndExact() {
    FundingConsumptionPlan plan =
        FundingLotFifoPlanner.plan(
            "operation-4",
            "wallet-1",
            Decimal4.of("0.30009"),
            List.of(
                lot("lot-1", "wallet-1", FundingLotType.REAL_RECHARGE, "1", "1", at(1), true)));
    Decimal4 total =
        plan.allocations().stream()
            .map(ConsumptionAllocation::allocatedPoints4)
            .reduce(Decimal4.zero(), Decimal4::add);

    check(total.equals(Decimal4.of("0.3000")), "allocation total equals truncated request");
    expectThrows(UnsupportedOperationException.class, () -> plan.updatedLots().clear());
    expectThrows(UnsupportedOperationException.class, () -> plan.allocations().clear());
    passed++;
  }

  private static FundingLot lot(
      String id,
      String walletId,
      FundingLotType type,
      String original,
      String remaining,
      Instant creditedAt,
      boolean benefitEligible) {
    return new FundingLot(
        id,
        walletId,
        type,
        Decimal4.of(original),
        Decimal4.of(remaining),
        creditedAt,
        benefitEligible);
  }

  private static Instant at(long seconds) {
    return Instant.parse("2026-09-04T00:00:00Z").plusSeconds(seconds);
  }

  private static Decimal4 remaining(FundingConsumptionPlan plan, String lotId) {
    return plan.updatedLots().stream()
        .filter(lot -> lot.id().equals(lotId))
        .findFirst()
        .orElseThrow()
        .remainingPoints4();
  }

  private static void assertAllocation(
      ConsumptionAllocation allocation,
      String operationId,
      String lotId,
      String walletId,
      String amount,
      FundingLotType lotType,
      boolean benefitEligible) {
    check(allocation.operationId().equals(operationId), "allocation operation");
    check(allocation.lotId().equals(lotId), "allocation lot");
    check(allocation.walletId().equals(walletId), "allocation wallet");
    check(allocation.allocatedPoints4().equals(Decimal4.of(amount)), "allocation amount");
    check(allocation.lotType() == lotType, "allocation type");
    check(allocation.benefitEligible() == benefitEligible, "allocation eligibility");
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
}
