package com.xzwl.cloud.promotion.domain.settlement;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.util.Objects;

/** Immutable instruction to reverse the exact cash released by a successful allocation. */
public record CashReleaseReversalAction(
    String sourceSettlementId,
    String sourceAllocationId,
    String sourceFundingLotId,
    String sourceWalletOwnerUserId,
    long referralBindingVersion,
    String beneficiaryPromoterUserId,
    CashTier cashTier,
    Decimal4 amount4) {

  public CashReleaseReversalAction {
    sourceSettlementId = PromotionIdentifiers.requireId(sourceSettlementId, "sourceSettlementId");
    sourceAllocationId = PromotionIdentifiers.requireId(sourceAllocationId, "sourceAllocationId");
    sourceFundingLotId = PromotionIdentifiers.requireId(sourceFundingLotId, "sourceFundingLotId");
    sourceWalletOwnerUserId =
        PromotionIdentifiers.requireId(sourceWalletOwnerUserId, "sourceWalletOwnerUserId");
    if (referralBindingVersion < 1) {
      throw new IllegalArgumentException("referralBindingVersion must be positive");
    }
    beneficiaryPromoterUserId =
        PromotionIdentifiers.requireId(beneficiaryPromoterUserId, "beneficiaryPromoterUserId");
    cashTier = Objects.requireNonNull(cashTier, "cashTier");
    amount4 = Objects.requireNonNull(amount4, "amount4");
    if (sourceWalletOwnerUserId.equals(beneficiaryPromoterUserId)) {
      throw new IllegalArgumentException("a cash reversal cannot pay the source wallet owner");
    }
    if (cashTier == CashTier.NONE) {
      throw new IllegalArgumentException("cash release reversal requires a paying cash tier");
    }
    if (amount4.compareTo(Decimal4.ZERO) <= 0) {
      throw new IllegalArgumentException("amount4 must be positive");
    }
  }
}
