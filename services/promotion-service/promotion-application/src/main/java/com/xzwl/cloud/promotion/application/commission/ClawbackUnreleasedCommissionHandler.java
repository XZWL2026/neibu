package com.xzwl.cloud.promotion.application.commission;

import com.xzwl.cloud.promotion.domain.commission.CommissionAccount;
import com.xzwl.cloud.promotion.domain.commission.CommissionEntry;
import java.util.Objects;

/** Coordinates recharge-refund clawback while preserving domain ledger invariants. */
public final class ClawbackUnreleasedCommissionHandler {

  private final CommissionStore store;

  public ClawbackUnreleasedCommissionHandler(CommissionStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public CommissionEntry handle(ClawbackUnreleasedCommissionCommand command) {
    ClawbackUnreleasedCommissionCommand requested =
        Objects.requireNonNull(command, "command");
    CommissionAccount account = store.load(requested.promoterUserId());
    CommissionEntry result =
        account.clawbackUnreleasedRefund(
            requested.sourceEventId(),
            requested.orderId(),
            requested.fundingLotId(),
            requested.amount4(),
            requested.createdAt());
    store.save(account);
    return result;
  }
}
