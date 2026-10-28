package com.xzwl.cloud.promotion.domain.settlement;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.util.Objects;

/** Immutable instruction to reverse the exact points granted by a successful allocation. */
public record PointRewardReversalAction(
    String sourceSettlementId,
    String sourceAllocationId,
    String sourceFundingLotId,
    String sourceWalletOwnerUserId,
    long referralBindingVersion,
    String beneficiaryUserId,
    Decimal4 points4) {

  public PointRewardReversalAction {
    sourceSettlementId = PromotionIdentifiers.requireId(sourceSettlementId, "sourceSettlementId");
    sourceAllocationId = PromotionIdentifiers.requireId(sourceAllocationId, "sourceAllocationId");
    sourceFundingLotId = PromotionIdentifiers.requireId(sourceFundingLotId, "sourceFundingLotId");
    sourceWalletOwnerUserId =
        PromotionIdentifiers.requireId(sourceWalletOwnerUserId, "sourceWalletOwnerUserId");
    if (referralBindingVersion < 1) {
      throw new IllegalArgumentException("referralBindingVersion must be positive");
    }
    beneficiaryUserId = PromotionIdentifiers.requireId(beneficiaryUserId, "beneficiaryUserId");
    points4 = Objects.requireNonNull(points4, "points4");
    if (sourceWalletOwnerUserId.equals(beneficiaryUserId)) {
      throw new IllegalArgumentException("a point reversal cannot pay the source wallet owner");
    }
    if (points4.compareTo(Decimal4.ZERO) <= 0) {
      throw new IllegalArgumentException("points4 must be positive");
    }
  }
}
