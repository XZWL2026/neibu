package com.xzwl.cloud.billing.application.funding;

import com.xzwl.cloud.billing.domain.funding.ConsumptionAllocation;
import com.xzwl.cloud.billing.domain.funding.FundingLot;
import com.xzwl.cloud.billing.domain.funding.FundingLotType;
import com.xzwl.cloud.billing.domain.funding.FundingRefundPlan;
import com.xzwl.cloud.billing.domain.funding.FundingRefundRequest;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RefundFundingHandlerTest {

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() throws Exception { main(new String[0]); }

  public static void main(String[] args) {
    restoresRefundedPointsAndReplaysTheSameRefundBatch();
    System.out.println("RefundFundingHandlerTest passed: 1");
  }

  private static void restoresRefundedPointsAndReplaysTheSameRefundBatch() {
    RecordingStore store = new RecordingStore();
    store.lots = List.of(
        new FundingLot("lot-1", "wallet-1", FundingLotType.REAL_RECHARGE,
            Decimal4.of("10"), Decimal4.of("4"), Instant.parse("2026-09-05T00:00:00Z"), true));
    store.allocations = List.of(
        new ConsumptionAllocation("operation-1", "lot-1", "wallet-1", Decimal4.of("6"),
            FundingLotType.REAL_RECHARGE, true));
    RefundFundingHandler handler = new RefundFundingHandler(store);
    List<FundingRefundRequest> requests = List.of(
        new FundingRefundRequest("refund-1", "operation-1", "lot-1", Decimal4.of("2")));

    FundingRefundPlan first = handler.handle(
        new RefundFundingCommand("wallet-1", "refund-batch-1", requests));
    FundingRefundPlan replay = handler.handle(
        new RefundFundingCommand("wallet-1", "refund-batch-1", requests));

    check(first.updatedLots().getFirst().remainingPoints4().equals(Decimal4.of("6")),
        "refunded points are restored");
    check(first.refundAllocations().getFirst().refundedPoints4().equals(Decimal4.of("2")),
        "refund allocation is recorded");
    check(replay == first, "same refund batch is replayed");
    check(store.calls.equals(List.of("find", "load", "allocations", "refunds", "save", "find")),
        "refund call order");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }

  private static final class RecordingStore implements RefundStore {
    private final List<String> calls = new ArrayList<>();
    private List<FundingLot> lots = List.of();
    private List<ConsumptionAllocation> allocations = List.of();
    private FundingRefundPlan saved;

    @Override
    public Optional<FundingRefundPlan> findRefund(String walletId, String refundBatchId) {
      calls.add("find");
      return Optional.ofNullable(saved);
    }

    @Override
    public List<FundingLot> loadLots(String walletId) {
      calls.add("load");
      return lots;
    }

    @Override
    public List<ConsumptionAllocation> loadAllocations(String walletId) {
      calls.add("allocations");
      return allocations;
    }

    @Override
    public List<com.xzwl.cloud.billing.domain.funding.RefundAllocation> loadRefundAllocations(
        String walletId) {
      calls.add("refunds");
      return List.of();
    }

    @Override
    public void saveRefund(String walletId, String refundBatchId, FundingRefundPlan plan) {
      calls.add("save");
      saved = plan;
      lots = plan.updatedLots();
    }
  }
}
