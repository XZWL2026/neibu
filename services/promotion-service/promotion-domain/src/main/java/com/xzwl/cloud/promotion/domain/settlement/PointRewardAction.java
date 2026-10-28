package com.xzwl.cloud.promotion.domain.settlement;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.util.Objects;

/** Immutable instruction to grant referral points after a successful allocation. */
public record PointRewardAction(String beneficiaryUserId, Decimal4 points4) {

  public PointRewardAction {
    beneficiaryUserId = PromotionIdentifiers.requireId(beneficiaryUserId, "beneficiaryUserId");
    points4 = Objects.requireNonNull(points4, "points4");
    if (points4.compareTo(Decimal4.ZERO) <= 0) {
      throw new IllegalArgumentException("points4 must be positive");
    }
  }
}
