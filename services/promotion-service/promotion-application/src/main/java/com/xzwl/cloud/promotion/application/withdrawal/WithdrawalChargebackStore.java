package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.withdrawal.Withdrawal;

/** Persistence port used by the withdrawal chargeback compensation use case. */
public interface WithdrawalChargebackStore {

  Withdrawal load(String withdrawalId);

  void save(Withdrawal withdrawal);
}
