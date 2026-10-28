package com.xzwl.cloud.billing.domain.settlement;

import com.xzwl.cloud.billing.domain.funding.ConsumptionAllocation;
import com.xzwl.cloud.billing.domain.funding.FundingLotType;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.lang.reflect.Executable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class TaskSettlementTest {

  private static int passed;

  public static void main(String[] args) {
    pendingAndProcessingHaveNoSettlementFacts();
    partialSuccessCreatesOnlyPositiveFactsAndTotalsSeventyFive();
    failedIsTerminalAndHasNoSettlementFacts();
    terminalCommandsAreIdempotentOnlyForTheSameCommandAndSemantics();
    expectedVersionPreventsCompetingWorkers();
    explicitSuccessInputsMustCoverEveryAllocationWithValidAmounts();
    duplicateSuccessInputsAreRejectedBeforeStateChanges();
    allocationIdentityWalletAndInputsAreImmutable();
    System.out.println("TaskSettlementTest passed: " + passed);
  }

  private static void pendingAndProcessingHaveNoSettlementFacts() {
    TaskSettlement pending = TaskSettlement.pending("task-1", "wallet-1", allocations(), 4);
    TaskSettlement processing = pending.observeProcessing();

    check(pending.status() == TaskSettlementStatus.PENDING, "pending status");
    check(pending.version() == 4, "pending version");
    check(pending.terminalCommandId().equals(Optional.empty()), "pending terminal command");
    check(pending.settlementId().equals(Optional.empty()), "pending settlement id");
    check(pending.successfulFacts().isEmpty(), "pending has no facts");
    check(pending.totalSuccessfulPoints4().equals(Decimal4.zero()), "pending total zero");
    check(processing == pending, "processing observation returns same aggregate");
    check(processing.version() == 4, "processing does not advance version");
    check(processing.successfulFacts().isEmpty(), "processing has no facts");
    passed++;
  }

  private static void partialSuccessCreatesOnlyPositiveFactsAndTotalsSeventyFive() {
    TaskSettlement settled =
        TaskSettlement.pending("task-1", "wallet-1", allocations(), 4)
            .succeed("command-1", "settlement-1", 4, success("25", "25", "25", "0"));

    check(settled.status() == TaskSettlementStatus.SUCCEEDED, "succeeded status");
    check(settled.version() == 5, "success advances version");
    check(settled.terminalCommandId().equals(Optional.of("command-1")), "success command fixed");
    check(settled.settlementId().equals(Optional.of("settlement-1")), "settlement id fixed");
    check(settled.totalSuccessfulPoints4().equals(Decimal4.of("75.0000")), "partial total");
    check(settled.successfulFacts().size() == 3, "only positive allocations become facts");

    SuccessfulAllocationFact real = fact(settled, "allocation-1");
    check(real.settlementId().equals("settlement-1"), "fact settlement id");
    check(real.taskId().equals("task-1"), "fact task id");
    check(real.lotId().equals("lot-1"), "fact lot id");
    check(real.walletId().equals("wallet-1"), "fact wallet id");
    check(real.lotType() == FundingLotType.REAL_RECHARGE, "fact lot type");
    check(real.benefitEligible(), "real recharge eligible");
    check(real.publishPromotionBenefit(), "eligible fact publishes benefit");
    check(real.successfulPoints4().equals(Decimal4.of("25")), "fact success amount");

    SuccessfulAllocationFact gift = fact(settled, "allocation-2");
    SuccessfulAllocationFact reward = fact(settled, "allocation-3");
    check(gift.lotType() == FundingLotType.GIFT && !gift.publishPromotionBenefit(), "gift never publishes benefit");
    check(reward.lotType() == FundingLotType.REFERRAL_REWARD && !reward.publishPromotionBenefit(), "reward never loops");
    check(settled.successfulFacts().stream().noneMatch(fact -> fact.allocationId().equals("allocation-4")), "zero allocation omitted");
    passed++;
  }

  private static void failedIsTerminalAndHasNoSettlementFacts() {
    TaskSettlement failed = TaskSettlement.pending("task-1", "wallet-1", allocations(), 4).fail("command-fail", 4);

    check(failed.status() == TaskSettlementStatus.FAILED, "failed status");
    check(failed.version() == 5, "failure advances version");
    check(failed.terminalCommandId().equals(Optional.of("command-fail")), "failure command recorded");
    check(failed.settlementId().isEmpty(), "failure has no settlement id");
    check(failed.successfulFacts().isEmpty(), "failure has no facts");
    check(failed.totalSuccessfulPoints4().equals(Decimal4.zero()), "failure total zero");
    passed++;
  }

  private static void terminalCommandsAreIdempotentOnlyForTheSameCommandAndSemantics() {
    List<SuccessfulAllocationInput> amounts = success("25", "25", "25", "0");
    TaskSettlement succeeded =
        TaskSettlement.pending("task-1", "wallet-1", allocations(), 4)
            .succeed("command-1", "settlement-1", 4, amounts);
    TaskSettlement failed = TaskSettlement.pending("task-2", "wallet-1", allocations(), 8).fail("command-fail", 8);

    check(succeeded.succeed("command-1", "settlement-1", 4, amounts) == succeeded, "same success replay");
    check(failed.fail("command-fail", 8) == failed, "same failure replay");
    expectThrows(IllegalStateException.class, () -> succeeded.succeed("command-2", "settlement-1", 4, amounts));
    expectThrows(IllegalStateException.class, () -> succeeded.succeed("command-1", "settlement-2", 4, amounts));
    expectThrows(
        IllegalStateException.class,
        () -> succeeded.succeed("command-1", "settlement-1", 4, success("25", "25", "24", "0")));
    expectThrows(IllegalStateException.class, () -> succeeded.fail("command-1", 4));
    expectThrows(
        IllegalStateException.class,
        () -> failed.succeed("command-fail", "settlement-late", 8, amounts));
    expectThrows(IllegalStateException.class, () -> failed.fail("command-other", 8));
    check(succeeded.status() == TaskSettlementStatus.SUCCEEDED, "success remains terminal");
    check(failed.status() == TaskSettlementStatus.FAILED, "failure remains terminal");
    passed++;
  }

  private static void expectedVersionPreventsCompetingWorkers() {
    TaskSettlement pending = TaskSettlement.pending("task-1", "wallet-1", allocations(), 4);

    expectThrows(IllegalStateException.class, () -> pending.fail("command-fail", 3));
    expectThrows(
        IllegalStateException.class,
        () -> pending.succeed("command-1", "settlement-1", 5, success("25", "25", "25", "0")));
    TaskSettlement succeeded = pending.succeed("command-1", "settlement-1", 4, success("25", "25", "25", "0"));
    expectThrows(
        IllegalStateException.class,
        () -> succeeded.succeed("command-1", "settlement-1", 5, success("25", "25", "25", "0")));
    check(pending.status() == TaskSettlementStatus.PENDING, "version failures do not mutate pending");
    passed++;
  }

  private static void explicitSuccessInputsMustCoverEveryAllocationWithValidAmounts() {
    TaskSettlement pending = TaskSettlement.pending("task-1", "wallet-1", allocations(), 4);
    List<SuccessfulAllocationInput> missing = new ArrayList<>(success("25", "25", "25", "0"));
    missing.remove(3);
    List<SuccessfulAllocationInput> unknown = new ArrayList<>(success("25", "25", "25", "0"));
    unknown.add(new SuccessfulAllocationInput("allocation-unknown", Decimal4.zero()));

    expectThrows(IllegalArgumentException.class, () -> pending.succeed("command-1", "settlement-1", 4, missing));
    expectThrows(IllegalArgumentException.class, () -> pending.succeed("command-1", "settlement-1", 4, unknown));
    expectThrows(
        IllegalArgumentException.class,
        () -> pending.succeed("command-1", "settlement-1", 4, success("-1", "25", "25", "0")));
    expectThrows(
        IllegalArgumentException.class,
        () -> pending.succeed("command-1", "settlement-1", 4, success("25.0001", "25", "25", "0")));
    expectThrows(
        IllegalArgumentException.class,
        () ->
            TaskSettlement.pending(
                "task-1",
                "wallet-1",
                List.of(allocations().get(0), new SettlementAllocation("allocation-1", allocation("operation-1", "lot-x", FundingLotType.GIFT, false))),
                0));
    passed++;
  }

  private static void duplicateSuccessInputsAreRejectedBeforeStateChanges() {
    TaskSettlement pending = TaskSettlement.pending("task-1", "wallet-1", allocations(), 4);
    List<SuccessfulAllocationInput> duplicate =
        List.of(
            new SuccessfulAllocationInput("allocation-1", Decimal4.of("25")),
            new SuccessfulAllocationInput("allocation-1", Decimal4.of("24")),
            new SuccessfulAllocationInput("allocation-2", Decimal4.of("25")),
            new SuccessfulAllocationInput("allocation-3", Decimal4.of("25")),
            new SuccessfulAllocationInput("allocation-4", Decimal4.zero()));

    expectThrows(
        IllegalArgumentException.class,
        () -> pending.succeed("command-1", "settlement-1", 4, duplicate));
    check(pending.status() == TaskSettlementStatus.PENDING, "duplicate input keeps status");
    check(pending.version() == 4, "duplicate input keeps version");
    Stream<? extends Executable> publicApi =
        Arrays.stream(TaskSettlement.class.getDeclaredMethods())
            .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()));
    check(
        publicApi.flatMap(method -> Arrays.stream(method.getParameterTypes()))
            .noneMatch(type -> type == Map.class),
        "public settlement API cannot accept Map success inputs");
    passed++;
  }

  private static void allocationIdentityWalletAndInputsAreImmutable() {
    List<SettlementAllocation> mutableAllocations = new ArrayList<>(allocations());
    TaskSettlement pending = TaskSettlement.pending("task-1", "wallet-1", mutableAllocations, 4);
    mutableAllocations.clear();
    List<SuccessfulAllocationInput> mutableAmounts = new ArrayList<>(success("25", "25", "25", "0"));
    TaskSettlement succeeded = pending.succeed("command-1", "settlement-1", 4, mutableAmounts);
    mutableAmounts.set(0, new SuccessfulAllocationInput("allocation-1", Decimal4.zero()));

    check(pending.allocations().size() == 4, "allocation input copied");
    check(succeeded.totalSuccessfulPoints4().equals(Decimal4.of("75")), "amount map copied");
    expectThrows(UnsupportedOperationException.class, () -> pending.allocations().clear());
    expectThrows(UnsupportedOperationException.class, () -> succeeded.successfulFacts().clear());
    expectThrows(
        IllegalArgumentException.class,
        () -> TaskSettlement.pending("task-1", "wallet-1", List.of(new SettlementAllocation("allocation-x", allocation("operation-1", "lot-x", FundingLotType.REAL_RECHARGE, true, "wallet-2"))), 0));
    expectThrows(IllegalArgumentException.class, () -> new SettlementAllocation(" ", allocation("operation-1", "lot-x", FundingLotType.GIFT, false)));
    expectThrows(IllegalArgumentException.class, () -> TaskSettlement.pending(" ", "wallet-1", allocations(), 0));
    passed++;
  }

  private static List<SettlementAllocation> allocations() {
    return List.of(
        new SettlementAllocation("allocation-1", allocation("operation-1", "lot-1", FundingLotType.REAL_RECHARGE, true)),
        new SettlementAllocation("allocation-2", allocation("operation-1", "lot-2", FundingLotType.GIFT, false)),
        new SettlementAllocation("allocation-3", allocation("operation-1", "lot-3", FundingLotType.REFERRAL_REWARD, false)),
        new SettlementAllocation("allocation-4", allocation("operation-1", "lot-4", FundingLotType.REAL_RECHARGE, true)));
  }

  private static ConsumptionAllocation allocation(
      String operationId, String lotId, FundingLotType lotType, boolean benefitEligible) {
    return allocation(operationId, lotId, lotType, benefitEligible, "wallet-1");
  }

  private static ConsumptionAllocation allocation(
      String operationId,
      String lotId,
      FundingLotType lotType,
      boolean benefitEligible,
      String walletId) {
    return new ConsumptionAllocation(operationId, lotId, walletId, Decimal4.of("25"), lotType, benefitEligible);
  }

  private static List<SuccessfulAllocationInput> success(
      String first, String second, String third, String fourth) {
    return List.of(
        new SuccessfulAllocationInput("allocation-1", Decimal4.of(first)),
        new SuccessfulAllocationInput("allocation-2", Decimal4.of(second)),
        new SuccessfulAllocationInput("allocation-3", Decimal4.of(third)),
        new SuccessfulAllocationInput("allocation-4", Decimal4.of(fourth)));
  }

  private static SuccessfulAllocationFact fact(TaskSettlement settlement, String allocationId) {
    return settlement.successfulFacts().stream()
        .filter(fact -> fact.allocationId().equals(allocationId))
        .findFirst()
        .orElseThrow();
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
