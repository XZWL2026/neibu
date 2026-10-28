package com.xzwl.cloud.promotion.domain.withdrawal;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.util.Collection;
import java.util.Objects;

public record WithdrawalSummary(Decimal4 inProgress, Decimal4 cumulativePaid) {

  public WithdrawalSummary {
    inProgress = Objects.requireNonNull(inProgress, "inProgress");
    cumulativePaid = Objects.requireNonNull(cumulativePaid, "cumulativePaid");
  }

  public static WithdrawalSummary from(Collection<Withdrawal> withdrawals) {
    Decimal4 inProgress = Decimal4.ZERO;
    Decimal4 paid = Decimal4.ZERO;
    for (Withdrawal withdrawal : Objects.requireNonNull(withdrawals, "withdrawals")) {
      Withdrawal item = Objects.requireNonNull(withdrawal, "withdrawal");
      switch (item.status()) {
        case WITHDRAWING, PENDING_PAYOUT -> inProgress = inProgress.add(item.amount());
        case PAID -> paid = paid.add(item.amount());
        case REJECTED -> {
          // Terminal rejection is excluded from both user-facing balances.
        }
      }
    }
    return new WithdrawalSummary(inProgress, paid);
  }
}
