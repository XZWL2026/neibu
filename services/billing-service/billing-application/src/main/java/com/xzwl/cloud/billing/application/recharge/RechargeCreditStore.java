package com.xzwl.cloud.billing.application.recharge;

import java.util.Optional;

public interface RechargeCreditStore {

  Optional<RechargeCreditResult> findByOrderId(String orderId);

  /** Atomically creates/locks the wallet and stores balance, cash record and funding lot, once per order. */
  RechargeCreditResult creditAtomically(RechargeCreditRecord record);
}
