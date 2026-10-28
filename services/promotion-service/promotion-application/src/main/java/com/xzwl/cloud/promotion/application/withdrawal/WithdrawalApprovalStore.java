package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.withdrawal.Withdrawal;

/** Persistence port used by the withdrawal approval use case. */
public interface WithdrawalApprovalStore {

  Withdrawal load(String withdrawalId);

  void save(Withdrawal withdrawal);
}
