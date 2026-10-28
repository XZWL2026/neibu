package com.xzwl.cloud.promotion.application.commission;

import com.xzwl.cloud.promotion.domain.commission.CommissionAccount;
import com.xzwl.cloud.promotion.domain.commission.CommissionEntry;
import java.util.Objects;

/** Coordinates the idempotent commission freeze for one recharge funding lot. */
public final class FreezeCommissionHandler {

  private final CommissionStore store;

  public FreezeCommissionHandler(CommissionStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public CommissionEntry handle(FreezeCommissionCommand command) {
    FreezeCommissionCommand requested = Objects.requireNonNull(command, "command");
    CommissionAccount account = store.load(requested.promoterUserId());
    CommissionEntry result =
        account.freezeCommission(
            requested.sourceEventId(),
            requested.orderId(),
            requested.fundingLotId(),
            requested.amount4(),
            requested.referralBindingVersion(),
            requested.cashTier(),
            requested.createdAt());
    store.save(account);
    return result;
  }
}
