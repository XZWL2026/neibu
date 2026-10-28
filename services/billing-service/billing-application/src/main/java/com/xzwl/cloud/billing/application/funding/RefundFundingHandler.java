package com.xzwl.cloud.billing.application.funding;

import com.xzwl.cloud.billing.domain.funding.FundingRefundPlan;
import com.xzwl.cloud.billing.domain.funding.FundingRefundPlanner;
import java.util.Objects;

/** Coordinates idempotent restoration of consumed wallet funding. */
public final class RefundFundingHandler {

  private final RefundStore store;

  public RefundFundingHandler(RefundStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public FundingRefundPlan handle(RefundFundingCommand command) {
    RefundFundingCommand requested = Objects.requireNonNull(command, "command");
    java.util.Optional<FundingRefundPlan> existing =
        store.findRefund(requested.walletId(), requested.refundBatchId());
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }

    FundingRefundPlan plan =
        FundingRefundPlanner.plan(
            store.loadLots(requested.walletId()),
            store.loadAllocations(requested.walletId()),
            store.loadRefundAllocations(requested.walletId()),
            requested.requests());
    store.saveRefund(requested.walletId(), requested.refundBatchId(), plan);
    return plan;
  }
}
