package com.xzwl.cloud.promotion.domain.referral;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;

public record DirectInviterFacts(
    String inviterUserId,
    boolean approvedPromoter,
    BenefitSnapshot existingBenefitSnapshot) {

  public DirectInviterFacts {
    inviterUserId = PromotionIdentifiers.requireId(inviterUserId, "inviterUserId");
  }

  @Override
  public String toString() {
    return "DirectInviterFacts[approvedPromoter=%s, existingBenefitSnapshot=%s]"
        .formatted(approvedPromoter, existingBenefitSnapshot);
  }
}
