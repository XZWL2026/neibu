package com.xzwl.cloud.promotion.application.application;

import com.xzwl.cloud.promotion.domain.application.DecisionResult;
import com.xzwl.cloud.promotion.domain.application.PromotionApplication;
import java.util.Objects;

/** Coordinates promotion application approval without coupling it to adapters. */
public final class ApprovePromotionApplicationHandler {

  private final PromotionApplicationApprovalStore store;

  public ApprovePromotionApplicationHandler(PromotionApplicationApprovalStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public DecisionResult handle(ApprovePromotionApplicationCommand command) {
    ApprovePromotionApplicationCommand requested = Objects.requireNonNull(command, "command");
    PromotionApplication application = store.load(requested.applicationId());
    DecisionResult result = application.approve(requested.adminId(), requested.decisionTime());
    store.save(application);
    return result;
  }
}
