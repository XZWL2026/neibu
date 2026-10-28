package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.withdrawal.Withdrawal;
import com.xzwl.cloud.promotion.domain.withdrawal.WithdrawalTransitionResult;
import java.util.Objects;

/** Coordinates offline payout confirmation without coupling it to adapters. */
public final class MarkWithdrawalPaidHandler {

  private final WithdrawalPayoutStore store;

  public MarkWithdrawalPaidHandler(WithdrawalPayoutStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public WithdrawalTransitionResult handle(MarkWithdrawalPaidCommand command) {
    MarkWithdrawalPaidCommand requested = Objects.requireNonNull(command, "command");
    Withdrawal withdrawal = store.load(requested.withdrawalId());
    WithdrawalTransitionResult result =
        withdrawal.markPaid(requested.commandId(), requested.operatorId(), requested.changedAt());
    store.save(withdrawal);
    return result;
  }
}
