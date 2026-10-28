package com.xzwl.cloud.billing.application.funding;

import com.xzwl.cloud.billing.domain.funding.ConsumptionAllocation;
import com.xzwl.cloud.billing.domain.funding.FundingConsumptionPlan;
import com.xzwl.cloud.billing.domain.funding.FundingLotFifoPlanner;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;

/** Coordinates FIFO wallet consumption while preserving operation idempotency. */
public final class ConsumeFundingHandler {

  private final FundingStore store;

  public ConsumeFundingHandler(FundingStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public FundingConsumptionPlan handle(ConsumeFundingCommand command) {
    ConsumeFundingCommand requested = Objects.requireNonNull(command, "command");
    java.util.Optional<FundingConsumptionPlan> existing =
        store.findConsumption(requested.walletId(), requested.operationId());
    if (existing.isPresent()) {
      FundingConsumptionPlan plan = existing.orElseThrow();
      if (!allocatedTotal(plan).equals(requested.requestedPoints4())) {
        throw new IllegalStateException("operation id was replayed with a different amount");
      }
      return plan;
    }

    FundingConsumptionPlan plan =
        FundingLotFifoPlanner.plan(
            requested.operationId(),
            requested.walletId(),
            requested.requestedPoints4(),
            store.loadLots(requested.walletId()));
    store.saveConsumption(requested.walletId(), requested.operationId(), plan);
    return plan;
  }

  private static Decimal4 allocatedTotal(FundingConsumptionPlan plan) {
    Decimal4 total = Decimal4.zero();
    for (ConsumptionAllocation allocation : plan.allocations()) {
      total = total.add(allocation.allocatedPoints4());
    }
    return total;
  }
}
