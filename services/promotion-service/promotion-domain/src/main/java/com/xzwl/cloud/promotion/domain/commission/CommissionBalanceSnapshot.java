package com.xzwl.cloud.promotion.domain.commission;

import com.xzwl.cloud.promotion.domain.money.Decimal4;

public record CommissionBalanceSnapshot(
    Decimal4 frozen,
    Decimal4 available,
    Decimal4 withdrawing,
    Decimal4 withdrawn,
    Decimal4 cashDebt) {

  public CommissionBalanceSnapshot {
    CommissionAmounts.requireNonNegative(frozen, "frozen");
    CommissionAmounts.requireNonNegative(available, "available");
    CommissionAmounts.requireNonNegative(withdrawing, "withdrawing");
    CommissionAmounts.requireNonNegative(withdrawn, "withdrawn");
    CommissionAmounts.requireNonNegative(cashDebt, "cashDebt");
  }

  public static CommissionBalanceSnapshot zero() {
    return new CommissionBalanceSnapshot(
        Decimal4.ZERO, Decimal4.ZERO, Decimal4.ZERO, Decimal4.ZERO, Decimal4.ZERO);
  }

  public Decimal4 withdrawable() {
    return CommissionAmounts.compare(cashDebt, Decimal4.ZERO) > 0 ? Decimal4.ZERO : available;
  }

  CommissionBalanceSnapshot frozenBy(Decimal4 amount) {
    return new CommissionBalanceSnapshot(
        frozen.add(amount), available, withdrawing, withdrawn, cashDebt);
  }

  CommissionBalanceSnapshot released(Decimal4 amount) {
    return new CommissionBalanceSnapshot(
        frozen.subtract(amount), available.add(amount), withdrawing, withdrawn, cashDebt);
  }

  CommissionBalanceSnapshot reverseFromAvailable(Decimal4 amount) {
    return new CommissionBalanceSnapshot(
        frozen, available.subtract(CommissionAmounts.min(available, amount)), withdrawing, withdrawn, cashDebt);
  }

  CommissionBalanceSnapshot clawedBack(Decimal4 amount) {
    return new CommissionBalanceSnapshot(
        frozen.subtract(amount), available, withdrawing, withdrawn, cashDebt);
  }

  CommissionBalanceSnapshot debtCreated(Decimal4 amount) {
    return new CommissionBalanceSnapshot(
        frozen, available, withdrawing, withdrawn, cashDebt.add(amount));
  }

  CommissionBalanceSnapshot debtOffset(Decimal4 amount) {
    return new CommissionBalanceSnapshot(
        frozen, available.subtract(amount), withdrawing, withdrawn, cashDebt.subtract(amount));
  }

  CommissionBalanceSnapshot withdrawalRequested(Decimal4 amount) {
    return new CommissionBalanceSnapshot(
        frozen, available.subtract(amount), withdrawing.add(amount), withdrawn, cashDebt);
  }

  CommissionBalanceSnapshot withdrawalRejected(Decimal4 amount) {
    return new CommissionBalanceSnapshot(
        frozen, available.add(amount), withdrawing.subtract(amount), withdrawn, cashDebt);
  }

  CommissionBalanceSnapshot withdrawalPaid(Decimal4 amount) {
    return new CommissionBalanceSnapshot(
        frozen, available, withdrawing.subtract(amount), withdrawn.add(amount), cashDebt);
  }
}
