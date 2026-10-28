package com.xzwl.cloud.promotion.application.application;

import com.xzwl.cloud.promotion.domain.application.PromotionApplication;
import java.util.Objects;

/** Coordinates promotion application submission without coupling it to adapters. */
public final class SubmitPromotionApplicationHandler {

  private final PromotionApplicationSubmissionStore store;

  public SubmitPromotionApplicationHandler(PromotionApplicationSubmissionStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public PromotionApplication handle(SubmitPromotionApplicationCommand command) {
    SubmitPromotionApplicationCommand requested = Objects.requireNonNull(command, "command");
    PromotionApplication application =
        PromotionApplication.submit(
            requested.applicationId(),
            requested.applicant(),
            requested.reason(),
            requested.plannedChannel(),
            requested.agreementsAccepted(),
            requested.submittedAt());
    store.save(application);
    return application;
  }
}
