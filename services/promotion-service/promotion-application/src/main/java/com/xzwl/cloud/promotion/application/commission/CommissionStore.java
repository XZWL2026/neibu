package com.xzwl.cloud.promotion.application.commission;

import com.xzwl.cloud.promotion.domain.commission.CommissionAccount;

/** Persistence port for a promoter commission account. */
public interface CommissionStore {

  CommissionAccount load(String promoterUserId);

  void save(CommissionAccount account);
}
