package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.commission.CommissionBalanceSnapshot;
import com.xzwl.cloud.promotion.domain.payout.PayoutAccount;
import com.xzwl.cloud.promotion.domain.withdrawal.Withdrawal;
import java.util.Objects;
import java.util.Optional;

/** Coordinates a withdrawal request without coupling the application layer to adapters. */
public final class RequestWithdrawalHandler {

  private final WithdrawalStore store;

  public RequestWithdrawalHandler(WithdrawalStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public Withdrawal handle(WithdrawalCommand command) {
    WithdrawalCommand requested = Objects.requireNonNull(command, "command");

    Optional<Withdrawal> existing =
        store.findByRequestCommandId(requested.promoterId(), requested.commandId());
    if (existing.isPresent()) {
      Withdrawal withdrawal = existing.get();
      withdrawal.replayRequest(
          requested.commandId(), requested.amountInput(), requested.payoutAccountId());
      if (!sameRequestSemantics(withdrawal, requested)) {
        throw new IllegalStateException(
            "idempotency key was replayed with different request semantics");
      }
      return withdrawal;
    }

    AccountStatus accountStatus = store.loadPromoterAccountStatus(requested.promoterId());
    PayoutAccount payoutAccount =
        store.loadPayoutAccount(requested.promoterId(), requested.payoutAccountId());
    CommissionBalanceSnapshot commissionBalance =
        store.loadCommissionBalance(requested.promoterId());

    Withdrawal withdrawal =
        Withdrawal.request(
            requested.withdrawalId(),
            requested.promoterId(),
            accountStatus,
            payoutAccount,
            commissionBalance,
            requested.amountInput(),
            requested.minimumAmount(),
            requested.commandId(),
            requested.requestedAt());
    store.save(withdrawal);
    return withdrawal;
  }

  private static boolean sameRequestSemantics(
      Withdrawal withdrawal, WithdrawalCommand requested) {
    return Objects.equals(withdrawal.promoterId(), requested.promoterId())
        && Objects.equals(withdrawal.id(), requested.withdrawalId())
        && Objects.equals(withdrawal.minimumAmount(), requested.minimumAmount())
        && Objects.equals(withdrawal.requestedAt(), requested.requestedAt());
  }
}
