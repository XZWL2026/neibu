package com.xzwl.cloud.promotion.domain.referral;

import java.util.Objects;

public final class BenefitSnapshotResolver {

  private BenefitSnapshotResolver() {
  }

  public static BenefitSnapshot resolve(DirectInviterFacts directInviter) {
    DirectInviterFacts facts = Objects.requireNonNull(directInviter, "directInviter");
    if (facts.approvedPromoter()) {
      return BenefitSnapshot.cashOnly(facts.inviterUserId());
    }

    return BenefitSnapshot.pointsOnly(facts.inviterUserId());
  }
}
