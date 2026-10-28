package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.withdrawal.Withdrawal;

/** Persistence port used by the withdrawal rejection use case. */
public interface WithdrawalRejectionStore {

  Withdrawal load(String withdrawalId);

  void save(Withdrawal withdrawal);
}
