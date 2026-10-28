package com.xzwl.cloud.promotion.application.commission;

import com.xzwl.cloud.promotion.domain.commission.CommissionAccount;
import com.xzwl.cloud.promotion.domain.commission.CommissionWriteResult;
import java.util.Objects;

/** Coordinates commission release reversal while preserving domain ledger invariants. */
public final class ReverseCommissionReleaseHandler {

  private final CommissionStore store;

  public ReverseCommissionReleaseHandler(CommissionStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public CommissionWriteResult handle(ReverseCommissionReleaseCommand command) {
    ReverseCommissionReleaseCommand requested = Objects.requireNonNull(command, "command");
    CommissionAccount account = store.load(requested.promoterUserId());
    CommissionWriteResult result =
        account.reverseRelease(
            requested.sourceEventId(),
            requested.allocationId(),
            requested.amount4(),
            requested.createdAt());
    store.save(account);
    return result;
  }
}
