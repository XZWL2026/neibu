package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.withdrawal.Withdrawal;
import com.xzwl.cloud.promotion.domain.withdrawal.WithdrawalTransitionResult;
import java.util.Objects;

/** Coordinates the withdrawal approval use case without coupling it to adapters. */
public final class ApproveWithdrawalHandler {

  private final WithdrawalApprovalStore store;

  public ApproveWithdrawalHandler(WithdrawalApprovalStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public WithdrawalTransitionResult handle(ApproveWithdrawalCommand command) {
    ApproveWithdrawalCommand requested = Objects.requireNonNull(command, "command");
    Withdrawal withdrawal = store.load(requested.withdrawalId());
    WithdrawalTransitionResult result =
        withdrawal.approve(requested.commandId(), requested.operatorId(), requested.changedAt());
    store.save(withdrawal);
    return result;
  }
}
