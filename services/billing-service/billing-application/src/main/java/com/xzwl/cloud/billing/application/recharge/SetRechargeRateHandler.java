package com.xzwl.cloud.billing.application.recharge;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.recharge.RechargeRate;
import java.util.Objects;

/** Called after the interface adapter has authorized an administrator. */
public final class SetRechargeRateHandler {
  private final RechargeRateStore store;

  public SetRechargeRateHandler(RechargeRateStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public RechargeRate handle(Decimal4 pointsPerYuan4, long expectedVersion) {
    if (expectedVersion < 1 || expectedVersion == Long.MAX_VALUE) {
      throw new IllegalArgumentException("expectedVersion is invalid");
    }
    RechargeRate updated = new RechargeRate(pointsPerYuan4, expectedVersion + 1);
    store.save(updated);
    return updated;
  }
}
