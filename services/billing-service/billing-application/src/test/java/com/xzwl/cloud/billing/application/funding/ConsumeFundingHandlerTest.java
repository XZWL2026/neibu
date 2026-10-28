package com.xzwl.cloud.billing.application.funding;

import com.xzwl.cloud.billing.domain.funding.FundingConsumptionPlan;
import com.xzwl.cloud.billing.domain.funding.FundingLot;
import com.xzwl.cloud.billing.domain.funding.FundingLotType;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ConsumeFundingHandlerTest {

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() throws Exception { main(new String[0]); }

  public static void main(String[] args) {
    consumesLotsInFifoOrderAndReplaysTheSameOperation();
    System.out.println("ConsumeFundingHandlerTest passed: 1");
  }

  private static void consumesLotsInFifoOrderAndReplaysTheSameOperation() {
    RecordingStore store = new RecordingStore();
    store.lots = List.of(
        lot("lot-2", "2026-09-05T00:02:00Z", "4.0000"),
        lot("lot-1", "2026-09-05T00:01:00Z", "3.0000"));
    ConsumeFundingHandler handler = new ConsumeFundingHandler(store);

    FundingConsumptionPlan first = handler.handle(
        new ConsumeFundingCommand("wallet-1", "operation-1", Decimal4.of("5.0000")));
    FundingConsumptionPlan replay = handler.handle(
        new ConsumeFundingCommand("wallet-1", "operation-1", Decimal4.of("5.0000")));

    check(first.allocations().getFirst().lotId().equals("lot-1"), "oldest lot is consumed first");
    check(first.allocations().getFirst().allocatedPoints4().equals(Decimal4.of("3.0000")),
        "oldest lot is consumed fully");
    check(first.allocations().getLast().lotId().equals("lot-2"), "next lot is consumed second");
    check(first.allocations().getLast().allocatedPoints4().equals(Decimal4.of("2.0000")),
        "remaining points come from next lot");
    check(replay == first, "same operation is replayed without replanning");
    check(store.calls.equals(List.of("find", "load", "save", "find")), "idempotent call order");
  }

  private static FundingLot lot(String id, String creditedAt, String points) {
    return new FundingLot(id, "wallet-1", FundingLotType.REAL_RECHARGE,
        Decimal4.of(points), Decimal4.of(points), Instant.parse(creditedAt), true);
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }

  private static final class RecordingStore implements FundingStore {
    private final List<String> calls = new ArrayList<>();
    private List<FundingLot> lots = List.of();
    private FundingConsumptionPlan saved;

    @Override
    public Optional<FundingConsumptionPlan> findConsumption(String walletId, String operationId) {
      calls.add("find");
      return Optional.ofNullable(saved);
    }

    @Override
    public List<FundingLot> loadLots(String walletId) {
      calls.add("load");
      return lots;
    }

    @Override
    public void saveConsumption(String walletId, String operationId, FundingConsumptionPlan plan) {
      calls.add("save");
      saved = plan;
      lots = plan.updatedLots();
    }
  }
}
