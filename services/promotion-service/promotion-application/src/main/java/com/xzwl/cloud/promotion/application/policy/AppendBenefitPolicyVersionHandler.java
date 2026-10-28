package com.xzwl.cloud.promotion.application.policy;

import com.xzwl.cloud.promotion.domain.policy.BenefitPolicyHistory;
import com.xzwl.cloud.promotion.domain.policy.BenefitPolicyVersion;
import java.util.Objects;

/** Publishes one admin-supplied, effective-dated benefit policy version. */
public final class AppendBenefitPolicyVersionHandler {

  private final BenefitPolicyStore store;

  public AppendBenefitPolicyVersionHandler(BenefitPolicyStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public BenefitPolicyHistory handle(AppendBenefitPolicyVersionCommand command) {
    AppendBenefitPolicyVersionCommand requested =
        Objects.requireNonNull(command, "command");
    BenefitPolicyHistory current = store.load(requested.historyId());
    BenefitPolicyVersion latest = current.versions().getLast();
    if (latest.versionNo() != requested.expectedVersionNo()) {
      throw new IllegalStateException("benefit policy version changed concurrently");
    }

    BenefitPolicyVersion next =
        new BenefitPolicyVersion(
            current.historyId(),
            latest.versionNo() + 1,
            requested.effectiveFrom(),
            requested.globalPointRate6(),
            requested.defaultLevel1CashRate6(),
            requested.defaultLevel2CashRate6(),
            requested.cashRateOverrides());
    BenefitPolicyHistory updated = current.append(next);
    store.save(updated);
    return updated;
  }
}
