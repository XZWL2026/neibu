package com.xzwl.cloud.promotion.application.policy;

import com.xzwl.cloud.promotion.domain.policy.BenefitPolicyHistory;

/** Persistence port for the immutable benefit policy history. */
public interface BenefitPolicyStore {

  BenefitPolicyHistory load(String historyId);

  void save(BenefitPolicyHistory history);
}
