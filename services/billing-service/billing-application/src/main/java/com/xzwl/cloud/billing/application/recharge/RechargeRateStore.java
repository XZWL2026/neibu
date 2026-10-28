package com.xzwl.cloud.billing.application.recharge;

import com.xzwl.cloud.billing.domain.recharge.RechargeRate;

public interface RechargeRateStore {
  RechargeRate load();

  /** Inside a debit transaction, hold the current conversion rate until that transaction commits. */
  default RechargeRate loadForCharge() { return load(); }

  /** Atomically replace version (updated.version - 1); reject a stale update. */
  void save(RechargeRate updated);
}
