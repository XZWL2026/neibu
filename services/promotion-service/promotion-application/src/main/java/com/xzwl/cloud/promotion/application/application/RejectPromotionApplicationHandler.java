package com.xzwl.cloud.promotion.application.application;

import com.xzwl.cloud.promotion.domain.application.DecisionResult;
import com.xzwl.cloud.promotion.domain.application.PromotionApplication;
import java.util.Objects;

/** Coordinates promotion application rejection without coupling it to adapters. */
public final class RejectPromotionApplicationHandler {

  private final PromotionApplicationRejectionStore store;

  public RejectPromotionApplicationHandler(PromotionApplicationRejectionStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public DecisionResult handle(RejectPromotionApplicationCommand command) {
    RejectPromotionApplicationCommand requested = Objects.requireNonNull(command, "command");
    PromotionApplication application = store.load(requested.applicationId());
    DecisionResult result =
        application.reject(
            requested.adminId(), requested.rejectionReason(), requested.decisionTime());
    store.save(application);
    return result;
  }
}
