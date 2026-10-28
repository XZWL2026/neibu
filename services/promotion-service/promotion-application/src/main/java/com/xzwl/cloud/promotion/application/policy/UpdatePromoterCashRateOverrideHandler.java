package com.xzwl.cloud.promotion.application.policy;

import com.xzwl.cloud.promotion.domain.policy.BenefitPolicyHistory;
import com.xzwl.cloud.promotion.domain.policy.BenefitPolicyVersion;
import com.xzwl.cloud.promotion.domain.policy.PromoterCashRateOverride;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Appends a policy version containing one promoter-specific cash rate override. */
public final class UpdatePromoterCashRateOverrideHandler {

  private final BenefitPolicyStore store;

  public UpdatePromoterCashRateOverrideHandler(BenefitPolicyStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public BenefitPolicyHistory handle(UpdatePromoterCashRateOverrideCommand command) {
    UpdatePromoterCashRateOverrideCommand requested =
        Objects.requireNonNull(command, "command");
    BenefitPolicyHistory current = store.load(requested.historyId());
    BenefitPolicyVersion latest = current.versions().getLast();
    if (latest.versionNo() != requested.expectedVersionNo()) {
      throw new IllegalStateException("benefit policy version changed concurrently");
    }

    Map<String, PromoterCashRateOverride> overrides =
        new LinkedHashMap<>(latest.cashRateOverrides());
    PromoterCashRateOverride override =
        new PromoterCashRateOverride(
            requested.promoterUserId(),
            requested.level1CashRate6(),
            requested.level2CashRate6());
    overrides.put(override.promoterUserId(), override);
    BenefitPolicyHistory updated =
        current.append(
            new BenefitPolicyVersion(
                current.historyId(),
                latest.versionNo() + 1,
                requested.effectiveFrom(),
                latest.globalPointRate6(),
                latest.defaultLevel1CashRate6(),
                latest.defaultLevel2CashRate6(),
                overrides));
    store.save(updated);
    return updated;
  }
}
