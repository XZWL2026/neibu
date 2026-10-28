package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.withdrawal.Withdrawal;
import com.xzwl.cloud.promotion.domain.withdrawal.WithdrawalTransitionResult;
import java.util.Objects;

/** Coordinates withdrawal chargeback compensation without coupling it to adapters. */
public final class CompensateWithdrawalChargebackHandler {

  private final WithdrawalChargebackStore store;

  public CompensateWithdrawalChargebackHandler(WithdrawalChargebackStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public WithdrawalTransitionResult handle(CompensateWithdrawalChargebackCommand command) {
    CompensateWithdrawalChargebackCommand requested = Objects.requireNonNull(command, "command");
    Withdrawal withdrawal = store.load(requested.withdrawalId());
    WithdrawalTransitionResult result =
        withdrawal.compensateChargeback(
            requested.commandId(), requested.sourceOrderId(), requested.changedAt());
    store.save(withdrawal);
    return result;
  }
}
