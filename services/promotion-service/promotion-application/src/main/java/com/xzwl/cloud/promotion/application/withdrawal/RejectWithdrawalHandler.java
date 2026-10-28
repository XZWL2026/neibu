package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.withdrawal.Withdrawal;
import com.xzwl.cloud.promotion.domain.withdrawal.WithdrawalTransitionResult;
import java.util.Objects;

/** Coordinates the withdrawal rejection use case without coupling it to adapters. */
public final class RejectWithdrawalHandler {

  private final WithdrawalRejectionStore store;

  public RejectWithdrawalHandler(WithdrawalRejectionStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public WithdrawalTransitionResult handle(RejectWithdrawalCommand command) {
    RejectWithdrawalCommand requested = Objects.requireNonNull(command, "command");
    Withdrawal withdrawal = store.load(requested.withdrawalId());
    WithdrawalTransitionResult result =
        withdrawal.reject(
            requested.commandId(),
            requested.operatorId(),
            requested.reason(),
            requested.changedAt());
    store.save(withdrawal);
    return result;
  }
}
