package com.xzwl.cloud.billing.domain.funding;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class FundingRefundPlannerTest {

  private static final String WALLET_ID = "wallet-1";
  private static final Instant CREDITED_AT = Instant.parse("2026-09-04T00:00:00Z");
  private static int passed;

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() throws Exception { main(new String[0]); }

  public static void main(String[] args) {
    restoresExplicitAmountsToTheirOriginalLotsWithoutReallocation();
    exactReplayIsANoOp();
    conflictingReplayIsRejected();
    cumulativeRefundCannotExceedOriginalAllocation();
    restoredBalanceCannotExceedLotOriginalAmount();
    factsMustAgreeOnWalletTypeAndBenefitEligibility();
    duplicateAndUnknownKeysAreRejected();
    emptyAndInvalidRequestsAreRejected();
    planListsAreImmutable();
    System.out.println("FundingRefundPlannerTest passed: " + passed);
  }

  private static void restoresExplicitAmountsToTheirOriginalLotsWithoutReallocation() {
    List<FundingLot> lots =
        List.of(
            lot("real", FundingLotType.REAL_RECHARGE, "100", "20", true),
            lot("gift", FundingLotType.GIFT, "30", "0", false),
            lot("reward", FundingLotType.REFERRAL_REWARD, "20", "5", false));
    List<ConsumptionAllocation> allocations =
        List.of(
            allocation("consume-real", "real", "80", FundingLotType.REAL_RECHARGE, true),
            allocation("consume-gift", "gift", "30", FundingLotType.GIFT, false),
            allocation("consume-reward", "reward", "15", FundingLotType.REFERRAL_REWARD, false));

    FundingRefundPlan plan =
        FundingRefundPlanner.plan(
            lots,
            allocations,
            List.of(),
            List.of(
                request("refund-real", "consume-real", "real", "12.34569"),
                request("refund-gift", "consume-gift", "gift", "10"),
                request("refund-reward", "consume-reward", "reward", "5")));

    check(remaining(plan, "real").equals(Decimal4.of("32.3456")), "real restored exactly");
    check(remaining(plan, "gift").equals(Decimal4.of("10")), "gift restored exactly");
    check(remaining(plan, "reward").equals(Decimal4.of("10")), "reward restored exactly");
    check(plan.refundAllocations().size() == 3, "one fact per explicit request");
    assertRefund(
        plan.refundAllocations().get(0),
        "refund-real",
        "consume-real",
        "real",
        "12.3456",
        FundingLotType.REAL_RECHARGE,
        true);
    assertRefund(
        plan.refundAllocations().get(1),
        "refund-gift",
        "consume-gift",
        "gift",
        "10",
        FundingLotType.GIFT,
        false);
    assertRefund(
        plan.refundAllocations().get(2),
        "refund-reward",
        "consume-reward",
        "reward",
        "5",
        FundingLotType.REFERRAL_REWARD,
        false);
    check(lots.get(0).remainingPoints4().equals(Decimal4.of("20")), "input lot unchanged");
    passed++;
  }

  private static void exactReplayIsANoOp() {
    FundingLot lot = lot("real", FundingLotType.REAL_RECHARGE, "100", "35", true);
    ConsumptionAllocation allocation =
        allocation("consume-real", "real", "80", FundingLotType.REAL_RECHARGE, true);
    RefundAllocation existing =
        refund(
            "refund-1",
            "consume-real",
            "real",
            WALLET_ID,
            "15",
            FundingLotType.REAL_RECHARGE,
            true);

    FundingRefundPlan plan =
        FundingRefundPlanner.plan(
            List.of(lot),
            List.of(allocation),
            List.of(existing),
            List.of(request("refund-1", "consume-real", "real", "15")));

    check(plan.updatedLots().equals(List.of(lot)), "replay does not restore twice");
    check(plan.refundAllocations().isEmpty(), "replay emits no duplicate fact");
    passed++;
  }

  private static void conflictingReplayIsRejected() {
    List<FundingLot> lots =
        List.of(
            lot("real-a", FundingLotType.REAL_RECHARGE, "100", "20", true),
            lot("real-b", FundingLotType.REAL_RECHARGE, "100", "40", true));
    List<ConsumptionAllocation> allocations =
        List.of(
            allocation("consume-a", "real-a", "80", FundingLotType.REAL_RECHARGE, true),
            allocation("consume-b", "real-b", "60", FundingLotType.REAL_RECHARGE, true));
    RefundAllocation existing =
        refund(
            "refund-1",
            "consume-a",
            "real-a",
            WALLET_ID,
            "10",
            FundingLotType.REAL_RECHARGE,
            true);

    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                lots,
                allocations,
                List.of(existing),
                List.of(request("refund-1", "consume-a", "real-a", "11"))));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                lots,
                allocations,
                List.of(existing),
                List.of(request("refund-1", "consume-b", "real-b", "10"))));
    passed++;
  }

  private static void cumulativeRefundCannotExceedOriginalAllocation() {
    FundingLot lot = lot("real", FundingLotType.REAL_RECHARGE, "100", "50", true);
    ConsumptionAllocation allocation =
        allocation("consume-real", "real", "10", FundingLotType.REAL_RECHARGE, true);
    RefundAllocation existing =
        refund(
            "refund-1",
            "consume-real",
            "real",
            WALLET_ID,
            "6",
            FundingLotType.REAL_RECHARGE,
            true);

    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                List.of(lot),
                List.of(allocation),
                List.of(existing),
                List.of(request("refund-2", "consume-real", "real", "4.0001"))));
    passed++;
  }

  private static void restoredBalanceCannotExceedLotOriginalAmount() {
    FundingLot lot = lot("real", FundingLotType.REAL_RECHARGE, "100", "99", true);
    ConsumptionAllocation allocation =
        allocation("consume-real", "real", "10", FundingLotType.REAL_RECHARGE, true);

    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                List.of(lot),
                List.of(allocation),
                List.of(),
                List.of(request("refund-1", "consume-real", "real", "2"))));
    passed++;
  }

  private static void factsMustAgreeOnWalletTypeAndBenefitEligibility() {
    FundingLot real = lot("real", FundingLotType.REAL_RECHARGE, "100", "20", true);
    ConsumptionAllocation valid =
        allocation("consume-real", "real", "80", FundingLotType.REAL_RECHARGE, true);

    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                List.of(real),
                List.of(
                    new ConsumptionAllocation(
                        "consume-real",
                        "real",
                        "wallet-other",
                        Decimal4.of("80"),
                        FundingLotType.REAL_RECHARGE,
                        true)),
                List.of(),
                List.of(request("refund-1", "consume-real", "real", "1"))));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                List.of(real),
                List.of(allocation("consume-real", "real", "80", FundingLotType.GIFT, false)),
                List.of(),
                List.of(request("refund-1", "consume-real", "real", "1"))));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                List.of(real),
                List.of(allocation("consume-real", "real", "80", FundingLotType.REAL_RECHARGE, false)),
                List.of(),
                List.of(request("refund-1", "consume-real", "real", "1"))));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                List.of(real),
                List.of(valid),
                List.of(
                    refund(
                        "refund-1",
                        "consume-real",
                        "real",
                        "wallet-other",
                        "1",
                        FundingLotType.REAL_RECHARGE,
                        true)),
                List.of(request("refund-1", "consume-real", "real", "1"))));
    passed++;
  }

  private static void duplicateAndUnknownKeysAreRejected() {
    FundingLot real = lot("real", FundingLotType.REAL_RECHARGE, "100", "20", true);
    ConsumptionAllocation valid =
        allocation("consume-real", "real", "80", FundingLotType.REAL_RECHARGE, true);
    FundingRefundRequest validRequest = request("refund-1", "consume-real", "real", "1");

    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                List.of(real),
                List.of(valid, valid),
                List.of(),
                List.of(validRequest)));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                List.of(real),
                List.of(valid),
                List.of(),
                List.of(validRequest, validRequest)));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                List.of(real, real),
                List.of(valid),
                List.of(),
                List.of(validRequest)));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                List.of(real),
                List.of(valid),
                List.of(),
                List.of(request("refund-1", "missing-operation", "real", "1"))));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            FundingRefundPlanner.plan(
                List.of(real),
                List.of(valid),
                List.of(),
                List.of(request("refund-1", "consume-real", "missing-lot", "1"))));
    passed++;
  }

  private static void emptyAndInvalidRequestsAreRejected() {
    FundingLot lot = lot("real", FundingLotType.REAL_RECHARGE, "100", "20", true);
    ConsumptionAllocation allocation =
        allocation("consume-real", "real", "80", FundingLotType.REAL_RECHARGE, true);

    expectThrows(
        IllegalArgumentException.class,
        () -> FundingRefundPlanner.plan(List.of(lot), List.of(allocation), List.of(), List.of()));
    expectThrows(
        IllegalArgumentException.class,
        () -> new FundingRefundRequest(" ", "consume-real", "real", Decimal4.of("1")));
    expectThrows(
        IllegalArgumentException.class,
        () -> new FundingRefundRequest("refund-1", "consume-real", "real", Decimal4.zero()));
    expectThrows(
        IllegalArgumentException.class,
        () -> new FundingRefundRequest("refund-1", "consume-real", "real", Decimal4.of("-1")));
    expectThrows(
        NullPointerException.class,
        () -> new FundingRefundRequest("refund-1", "consume-real", "real", null));
    passed++;
  }

  private static void planListsAreImmutable() {
    FundingRefundPlan plan =
        FundingRefundPlanner.plan(
            List.of(lot("real", FundingLotType.REAL_RECHARGE, "100", "20", true)),
            List.of(
                allocation("consume-real", "real", "80", FundingLotType.REAL_RECHARGE, true)),
            List.of(),
            List.of(request("refund-1", "consume-real", "real", "1")));

    expectThrows(UnsupportedOperationException.class, () -> plan.updatedLots().clear());
    expectThrows(UnsupportedOperationException.class, () -> plan.refundAllocations().clear());
    passed++;
  }

  private static FundingLot lot(
      String id, FundingLotType type, String original, String remaining, boolean benefitEligible) {
    return new FundingLot(
        id,
        WALLET_ID,
        type,
        Decimal4.of(original),
        Decimal4.of(remaining),
        CREDITED_AT,
        benefitEligible);
  }

  private static ConsumptionAllocation allocation(
      String operationId,
      String lotId,
      String amount,
      FundingLotType lotType,
      boolean benefitEligible) {
    return new ConsumptionAllocation(
        operationId,
        lotId,
        WALLET_ID,
        Decimal4.of(amount),
        lotType,
        benefitEligible);
  }

  private static FundingRefundRequest request(
      String refundId, String operationId, String lotId, String amount) {
    return new FundingRefundRequest(refundId, operationId, lotId, Decimal4.of(amount));
  }

  private static RefundAllocation refund(
      String refundId,
      String operationId,
      String lotId,
      String walletId,
      String amount,
      FundingLotType lotType,
      boolean benefitEligible) {
    return new RefundAllocation(
        refundId,
        operationId,
        lotId,
        walletId,
        Decimal4.of(amount),
        lotType,
        benefitEligible);
  }

  private static Decimal4 remaining(FundingRefundPlan plan, String lotId) {
    return plan.updatedLots().stream()
        .filter(lot -> lot.id().equals(lotId))
        .findFirst()
        .orElseThrow()
        .remainingPoints4();
  }

  private static void assertRefund(
      RefundAllocation allocation,
      String refundId,
      String operationId,
      String lotId,
      String amount,
      FundingLotType lotType,
      boolean benefitEligible) {
    check(allocation.refundId().equals(refundId), "refund id");
    check(allocation.operationId().equals(operationId), "original operation id");
    check(allocation.lotId().equals(lotId), "original lot id");
    check(allocation.walletId().equals(WALLET_ID), "wallet id");
    check(allocation.refundedPoints4().equals(Decimal4.of(amount)), "refunded amount");
    check(allocation.lotType() == lotType, "lot type");
    check(allocation.benefitEligible() == benefitEligible, "benefit eligibility");
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
}
