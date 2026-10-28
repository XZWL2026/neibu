package com.xzwl.cloud.billing.domain.settlement;

import com.xzwl.cloud.billing.domain.funding.FundingLotType;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SuccessfulAllocationReversalPlannerTest {

  private static int passed;

  public static void main(String[] args) {
    createsExactFactsFromFrozenSuccessfulAllocations();
    exactReplayIsANoOpAndConflictsAreRejected();
    cumulativeReversalCannotExceedOriginalSuccess();
    duplicateAndUnknownKeysAreRejected();
    invalidAndEmptyRequestsAreRejected();
    existingFactsMustPreserveEveryFrozenSourceField();
    giftAndReferralRewardNeverPublishPromotionBenefitReversals();
    resultAndFactsAreDeeplyImmutable();
    System.out.println("SuccessfulAllocationReversalPlannerTest passed: " + passed);
  }

  private static void createsExactFactsFromFrozenSuccessfulAllocations() {
    List<SuccessfulAllocationReversalFact> reversals =
        SuccessfulAllocationReversalPlanner.plan(
            originals(),
            List.of(),
            List.of(
                request("reverse-real", "settlement-1", "allocation-real", "1.23459"),
                request("reverse-gift", "settlement-1", "allocation-gift", "2"),
                request("reverse-reward", "settlement-1", "allocation-reward", "1"),
                request("reverse-muted", "settlement-1", "allocation-muted", "3")));

    check(reversals.size() == 4, "one fact per new request");
    assertFact(
        reversals.get(0),
        "reverse-real",
        "settlement-1",
        "task-1",
        "allocation-real",
        "lot-real",
        "wallet-1",
        FundingLotType.REAL_RECHARGE,
        true,
        "1.2345",
        true);
    assertFact(
        reversals.get(1),
        "reverse-gift",
        "settlement-1",
        "task-1",
        "allocation-gift",
        "lot-gift",
        "wallet-1",
        FundingLotType.GIFT,
        false,
        "2",
        false);
    assertFact(
        reversals.get(2),
        "reverse-reward",
        "settlement-1",
        "task-1",
        "allocation-reward",
        "lot-reward",
        "wallet-1",
        FundingLotType.REFERRAL_REWARD,
        false,
        "1",
        false);
    check(!reversals.get(3).publishPromotionBenefitReversal(), "muted original stays muted");
    passed++;
  }

  private static void exactReplayIsANoOpAndConflictsAreRejected() {
    SuccessfulAllocationReversalFact existing =
        reversal(
            "reverse-real",
            "settlement-1",
            "task-1",
            "allocation-real",
            "lot-real",
            "wallet-1",
            FundingLotType.REAL_RECHARGE,
            true,
            "4",
            true);

    List<SuccessfulAllocationReversalFact> replay =
        SuccessfulAllocationReversalPlanner.plan(
            originals(),
            List.of(existing),
            List.of(request("reverse-real", "settlement-1", "allocation-real", "4")));

    check(replay.isEmpty(), "exact replay emits no duplicate fact");
    expectThrows(
        IllegalArgumentException.class,
        () ->
            SuccessfulAllocationReversalPlanner.plan(
                originals(),
                List.of(existing),
                List.of(request("reverse-real", "settlement-1", "allocation-real", "4.0001"))));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            SuccessfulAllocationReversalPlanner.plan(
                originals(),
                List.of(existing),
                List.of(request("reverse-real", "settlement-1", "allocation-gift", "4"))));
    passed++;
  }

  private static void cumulativeReversalCannotExceedOriginalSuccess() {
    SuccessfulAllocationReversalFact existing =
        reversal(
            "reverse-1",
            "settlement-1",
            "task-1",
            "allocation-real",
            "lot-real",
            "wallet-1",
            FundingLotType.REAL_RECHARGE,
            true,
            "6",
            true);

    List<SuccessfulAllocationReversalFact> exact =
        SuccessfulAllocationReversalPlanner.plan(
            originals(),
            List.of(existing),
            List.of(request("reverse-2", "settlement-1", "allocation-real", "4")));
    check(exact.get(0).reversedPoints4().equals(Decimal4.of("4")), "exact remainder accepted");

    expectThrows(
        IllegalArgumentException.class,
        () ->
            SuccessfulAllocationReversalPlanner.plan(
                originals(),
                List.of(existing),
                List.of(request("reverse-2", "settlement-1", "allocation-real", "4.0001"))));
    passed++;
  }

  private static void duplicateAndUnknownKeysAreRejected() {
    SuccessfulAllocationFact original = originals().get(0);
    SuccessfulAllocationReversalRequest duplicateRequest =
        request("reverse-1", "settlement-1", "allocation-real", "1");
    SuccessfulAllocationReversalFact duplicateExisting =
        reversal(
            "reverse-existing",
            "settlement-1",
            "task-1",
            "allocation-real",
            "lot-real",
            "wallet-1",
            FundingLotType.REAL_RECHARGE,
            true,
            "1",
            true);

    expectThrows(
        IllegalArgumentException.class,
        () ->
            SuccessfulAllocationReversalPlanner.plan(
                List.of(original, original), List.of(), List.of(duplicateRequest)));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            SuccessfulAllocationReversalPlanner.plan(
                originals(), List.of(duplicateExisting, duplicateExisting), List.of(duplicateRequest)));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            SuccessfulAllocationReversalPlanner.plan(
                originals(), List.of(), List.of(duplicateRequest, duplicateRequest)));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            SuccessfulAllocationReversalPlanner.plan(
                originals(),
                List.of(),
                List.of(request("reverse-unknown", "settlement-missing", "allocation-real", "1"))));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            SuccessfulAllocationReversalPlanner.plan(
                originals(),
                List.of(),
                List.of(request("reverse-unknown", "settlement-1", "allocation-missing", "1"))));
    passed++;
  }

  private static void invalidAndEmptyRequestsAreRejected() {
    expectThrows(
        IllegalArgumentException.class,
        () -> SuccessfulAllocationReversalPlanner.plan(originals(), List.of(), List.of()));
    expectThrows(
        IllegalArgumentException.class,
        () -> new SuccessfulAllocationReversalRequest(" ", "settlement-1", "allocation-real", Decimal4.of("1")));
    expectThrows(
        IllegalArgumentException.class,
        () -> new SuccessfulAllocationReversalRequest("reverse-1", " ", "allocation-real", Decimal4.of("1")));
    expectThrows(
        IllegalArgumentException.class,
        () -> new SuccessfulAllocationReversalRequest("reverse-1", "settlement-1", " ", Decimal4.of("1")));
    expectThrows(
        IllegalArgumentException.class,
        () -> new SuccessfulAllocationReversalRequest("reverse-1", "settlement-1", "allocation-real", Decimal4.zero()));
    expectThrows(
        IllegalArgumentException.class,
        () -> new SuccessfulAllocationReversalRequest("reverse-1", "settlement-1", "allocation-real", Decimal4.of("-1")));
    expectThrows(
        NullPointerException.class,
        () -> new SuccessfulAllocationReversalRequest("reverse-1", "settlement-1", "allocation-real", null));
    expectThrows(
        NullPointerException.class,
        () -> SuccessfulAllocationReversalPlanner.plan(null, List.of(), List.of()));
    expectThrows(
        NullPointerException.class,
        () -> SuccessfulAllocationReversalPlanner.plan(originals(), null, List.of()));
    expectThrows(
        NullPointerException.class,
        () -> SuccessfulAllocationReversalPlanner.plan(originals(), List.of(), null));
    passed++;
  }

  private static void existingFactsMustPreserveEveryFrozenSourceField() {
    expectTamperedExisting(
        reversal(
            "reverse-1", "settlement-1", "task-other", "allocation-real", "lot-real", "wallet-1",
            FundingLotType.REAL_RECHARGE, true, "1", true));
    expectTamperedExisting(
        reversal(
            "reverse-1", "settlement-1", "task-1", "allocation-real", "lot-other", "wallet-1",
            FundingLotType.REAL_RECHARGE, true, "1", true));
    expectTamperedExisting(
        reversal(
            "reverse-1", "settlement-1", "task-1", "allocation-real", "lot-real", "wallet-other",
            FundingLotType.REAL_RECHARGE, true, "1", true));
    expectTamperedExisting(
        reversal(
            "reverse-1", "settlement-1", "task-1", "allocation-real", "lot-real", "wallet-1",
            FundingLotType.GIFT, false, "1", false));
    expectTamperedExisting(
        reversal(
            "reverse-1", "settlement-1", "task-1", "allocation-real", "lot-real", "wallet-1",
            FundingLotType.REAL_RECHARGE, false, "1", false));
    expectTamperedExisting(
        reversal(
            "reverse-1", "settlement-1", "task-1", "allocation-real", "lot-real", "wallet-1",
            FundingLotType.REAL_RECHARGE, true, "1", false));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            SuccessfulAllocationReversalPlanner.plan(
                originals(),
                List.of(
                    reversal(
                        "reverse-unknown", "settlement-missing", "task-1", "allocation-real", "lot-real", "wallet-1",
                        FundingLotType.REAL_RECHARGE, true, "1", true)),
                List.of(request("reverse-new", "settlement-1", "allocation-real", "1"))));
    passed++;
  }

  private static void giftAndReferralRewardNeverPublishPromotionBenefitReversals() {
    expectThrows(
        IllegalArgumentException.class,
        () ->
            reversal(
                "reverse-gift", "settlement-1", "task-1", "allocation-gift", "lot-gift", "wallet-1",
                FundingLotType.GIFT, false, "1", true));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            reversal(
                "reverse-reward", "settlement-1", "task-1", "allocation-reward", "lot-reward", "wallet-1",
                FundingLotType.REFERRAL_REWARD, false, "1", true));
    passed++;
  }

  private static void resultAndFactsAreDeeplyImmutable() {
    ArrayList<SuccessfulAllocationReversalRequest> requests =
        new ArrayList<>(List.of(request("reverse-1", "settlement-1", "allocation-real", "1")));
    List<SuccessfulAllocationReversalFact> result =
        SuccessfulAllocationReversalPlanner.plan(originals(), List.of(), requests);
    requests.clear();

    expectThrows(UnsupportedOperationException.class, () -> result.clear());
    check(result.size() == 1, "result is detached from mutable request list");
    check(SuccessfulAllocationReversalRequest.class.isRecord(), "request is immutable record");
    check(SuccessfulAllocationReversalFact.class.isRecord(), "fact is immutable record");
    check(
        Arrays.stream(SuccessfulAllocationReversalFact.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList()
            .equals(
                List.of(
                    "reversalId",
                    "settlementId",
                    "taskId",
                    "allocationId",
                    "lotId",
                    "walletId",
                    "lotType",
                    "benefitEligible",
                    "reversedPoints4",
                    "publishPromotionBenefitReversal")),
        "fact contains only frozen source and reversal fields");
    passed++;
  }

  private static List<SuccessfulAllocationFact> originals() {
    return List.of(
        original("allocation-real", "lot-real", FundingLotType.REAL_RECHARGE, true, "10", true),
        original("allocation-gift", "lot-gift", FundingLotType.GIFT, false, "5", false),
        original("allocation-reward", "lot-reward", FundingLotType.REFERRAL_REWARD, false, "3", false),
        original("allocation-muted", "lot-muted", FundingLotType.REAL_RECHARGE, true, "8", false));
  }

  private static SuccessfulAllocationFact original(
      String allocationId,
      String lotId,
      FundingLotType lotType,
      boolean benefitEligible,
      String successfulPoints4,
      boolean publishPromotionBenefit) {
    return new SuccessfulAllocationFact(
        "settlement-1",
        "task-1",
        allocationId,
        lotId,
        "wallet-1",
        lotType,
        benefitEligible,
        Decimal4.of(successfulPoints4),
        publishPromotionBenefit);
  }

  private static SuccessfulAllocationReversalRequest request(
      String reversalId, String settlementId, String allocationId, String reversedPoints4) {
    return new SuccessfulAllocationReversalRequest(
        reversalId, settlementId, allocationId, Decimal4.of(reversedPoints4));
  }

  private static SuccessfulAllocationReversalFact reversal(
      String reversalId,
      String settlementId,
      String taskId,
      String allocationId,
      String lotId,
      String walletId,
      FundingLotType lotType,
      boolean benefitEligible,
      String reversedPoints4,
      boolean publishPromotionBenefitReversal) {
    return new SuccessfulAllocationReversalFact(
        reversalId,
        settlementId,
        taskId,
        allocationId,
        lotId,
        walletId,
        lotType,
        benefitEligible,
        Decimal4.of(reversedPoints4),
        publishPromotionBenefitReversal);
  }

  private static void expectTamperedExisting(SuccessfulAllocationReversalFact tampered) {
    expectThrows(
        IllegalArgumentException.class,
        () ->
            SuccessfulAllocationReversalPlanner.plan(
                originals(),
                List.of(tampered),
                List.of(request("reverse-new", "settlement-1", "allocation-real", "1"))));
  }

  private static void assertFact(
      SuccessfulAllocationReversalFact fact,
      String reversalId,
      String settlementId,
      String taskId,
      String allocationId,
      String lotId,
      String walletId,
      FundingLotType lotType,
      boolean benefitEligible,
      String reversedPoints4,
      boolean publishPromotionBenefitReversal) {
    check(fact.reversalId().equals(reversalId), "reversal id");
    check(fact.settlementId().equals(settlementId), "settlement id");
    check(fact.taskId().equals(taskId), "task id");
    check(fact.allocationId().equals(allocationId), "allocation id");
    check(fact.lotId().equals(lotId), "lot id");
    check(fact.walletId().equals(walletId), "wallet id");
    check(fact.lotType() == lotType, "lot type");
    check(fact.benefitEligible() == benefitEligible, "benefit eligibility");
    check(fact.reversedPoints4().equals(Decimal4.of(reversedPoints4)), "reversed points");
    check(
        fact.publishPromotionBenefitReversal() == publishPromotionBenefitReversal,
        "promotion benefit reversal publication");
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
