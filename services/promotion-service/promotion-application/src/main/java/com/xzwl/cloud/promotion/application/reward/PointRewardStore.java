package com.xzwl.cloud.promotion.application.reward;

import com.xzwl.cloud.promotion.domain.reward.PointRewardAccount;

/** Persistence port for a beneficiary's promotion point-reward ledger. */
public interface PointRewardStore {

  PointRewardAccount load(String beneficiaryUserId);

  void save(PointRewardAccount account);
}
