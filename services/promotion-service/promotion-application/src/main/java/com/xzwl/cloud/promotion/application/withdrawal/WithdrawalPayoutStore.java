package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.withdrawal.Withdrawal;

/** Persistence port used by the withdrawal payout confirmation use case. */
public interface WithdrawalPayoutStore {

  Withdrawal load(String withdrawalId);

  void save(Withdrawal withdrawal);
}
