package com.xzwl.cloud.promotion.application.application;

import com.xzwl.cloud.promotion.domain.application.PromotionApplication;

/** Persistence port used by the promotion application approval use case. */
public interface PromotionApplicationApprovalStore {

  PromotionApplication load(String applicationId);

  void save(PromotionApplication application);
}
