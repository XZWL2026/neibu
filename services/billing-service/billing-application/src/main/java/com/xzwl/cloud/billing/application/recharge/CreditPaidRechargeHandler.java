package com.xzwl.cloud.billing.application.recharge;

import java.util.Objects;

public final class CreditPaidRechargeHandler {

  private final RechargeCreditStore store;

  public CreditPaidRechargeHandler(RechargeCreditStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public RechargeCreditResult handle(CreditPaidRechargeCommand command) {
    CreditPaidRechargeCommand paid = Objects.requireNonNull(command, "command");
    return store.creditAtomically(new RechargeCreditRecord(paid.orderId(), paid.userId(),
        paid.walletId(), paid.keyId(), paid.amount4(), paid.rateSnapshot4(), paid.paidAt()));
  }
}
