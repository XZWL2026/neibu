package com.xzwl.cloud.promotion.domain.settlement;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.util.Objects;

/** Immutable instruction to release frozen commission after a successful allocation. */
public record CashReleaseAction(
    String beneficiaryPromoterUserId, CashTier cashTier, Decimal4 amount4) {

  public CashReleaseAction {
    beneficiaryPromoterUserId =
        PromotionIdentifiers.requireId(beneficiaryPromoterUserId, "beneficiaryPromoterUserId");
    cashTier = Objects.requireNonNull(cashTier, "cashTier");
    amount4 = Objects.requireNonNull(amount4, "amount4");
    if (cashTier == CashTier.NONE) {
      throw new IllegalArgumentException("cash release requires a paying cash tier");
    }
    if (amount4.compareTo(Decimal4.ZERO) <= 0) {
      throw new IllegalArgumentException("amount4 must be positive");
    }
  }
}
