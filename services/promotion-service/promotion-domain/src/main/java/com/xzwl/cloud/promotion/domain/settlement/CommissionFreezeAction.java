package com.xzwl.cloud.promotion.domain.settlement;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.util.Objects;

/** Immutable instruction to freeze order-time cash commission. */
public record CommissionFreezeAction(
    String sourceEventId,
    String orderId,
    String fundingLotId,
    String beneficiaryPromoterUserId,
    long referralBindingVersion,
    CashTier cashTier,
    Decimal4 amount4) {

  public CommissionFreezeAction {
    sourceEventId = PromotionIdentifiers.requireId(sourceEventId, "sourceEventId");
    orderId = PromotionIdentifiers.requireId(orderId, "orderId");
    fundingLotId = PromotionIdentifiers.requireId(fundingLotId, "fundingLotId");
    beneficiaryPromoterUserId =
        PromotionIdentifiers.requireId(beneficiaryPromoterUserId, "beneficiaryPromoterUserId");
    if (referralBindingVersion < 1) {
      throw new IllegalArgumentException("referralBindingVersion must be positive");
    }
    cashTier = Objects.requireNonNull(cashTier, "cashTier");
    amount4 = Objects.requireNonNull(amount4, "amount4");
    if (cashTier == CashTier.NONE) {
      throw new IllegalArgumentException("commission freeze requires a paying cash tier");
    }
    if (amount4.compareTo(Decimal4.ZERO) <= 0) {
      throw new IllegalArgumentException("amount4 must be positive");
    }
  }
}
