package com.xzwl.cloud.payment.domain.order;

import java.util.Objects;

/** Linearizable account gate for the one successful first-recharge type decision. */
public final class FirstRechargeTypeLock {

  private final String accountId;
  private RechargeType lockedType;

  private FirstRechargeTypeLock(String accountId) {
    if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("accountId must not be blank");
    this.accountId = accountId;
  }

  public static FirstRechargeTypeLock create(String accountId) {
    return new FirstRechargeTypeLock(accountId);
  }

  public String accountId() {
    return accountId;
  }

  public synchronized RechargeType lockedType() {
    return lockedType;
  }

  /** Returns true only when this call wins the previously-unset lock. */
  public synchronized boolean lock(RechargeType requestedType) {
    RechargeType requested = Objects.requireNonNull(requestedType, "requestedType");
    if (lockedType == null) {
      lockedType = requested;
      return true;
    }
    if (lockedType != requested) {
      throw new IllegalStateException("account first recharge type is already locked");
    }
    return false;
  }
}
