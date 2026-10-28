package com.xzwl.cloud.promotion.application.commission;

import com.xzwl.cloud.promotion.domain.commission.CommissionAccount;
import com.xzwl.cloud.promotion.domain.commission.CommissionWriteResult;
import java.util.Objects;

/** Coordinates commission release while leaving ledger invariants to the domain account. */
public final class ReleaseCommissionHandler {

  private final CommissionStore store;

  public ReleaseCommissionHandler(CommissionStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public CommissionWriteResult handle(ReleaseCommissionCommand command) {
    ReleaseCommissionCommand requested = Objects.requireNonNull(command, "command");
    CommissionAccount account = store.load(requested.promoterUserId());
    CommissionWriteResult result =
        account.releaseCommission(
            requested.sourceEventId(),
            requested.orderId(),
            requested.fundingLotId(),
            requested.allocationId(),
            requested.amount4(),
            requested.createdAt());
    store.save(account);
    return result;
  }
}
