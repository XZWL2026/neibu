package com.xzwl.cloud.promotion.application.application;

import com.xzwl.cloud.promotion.domain.application.PromotionApplication;

/** Persistence port used by the promotion application submission use case. */
public interface PromotionApplicationSubmissionStore {

  void save(PromotionApplication application);
}
