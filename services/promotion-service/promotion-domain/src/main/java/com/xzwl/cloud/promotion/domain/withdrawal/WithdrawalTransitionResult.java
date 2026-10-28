package com.xzwl.cloud.promotion.domain.withdrawal;

import java.util.Objects;
import java.util.Optional;

public final class WithdrawalTransitionResult {

  private final WithdrawalStatus status;
  private final Optional<WithdrawalBalanceAction> balanceAction;

  private WithdrawalTransitionResult(
      WithdrawalStatus status, Optional<WithdrawalBalanceAction> balanceAction) {
    this.status = Objects.requireNonNull(status, "status");
    this.balanceAction = Objects.requireNonNull(balanceAction, "balanceAction");
  }

  static WithdrawalTransitionResult withoutBalanceAction(WithdrawalStatus status) {
    return new WithdrawalTransitionResult(status, Optional.empty());
  }

  static WithdrawalTransitionResult withBalanceAction(
      WithdrawalStatus status, WithdrawalBalanceAction action) {
    return new WithdrawalTransitionResult(status, Optional.of(action));
  }

  public WithdrawalStatus status() {
    return status;
  }

  public Optional<WithdrawalBalanceAction> balanceAction() {
    return balanceAction;
  }
}
