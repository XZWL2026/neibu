package com.xzwl.cloud.promotion.domain.settlement;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import java.util.Objects;
import java.util.Optional;

/** Immutable benefit actions derived solely from one frozen allocation snapshot. */
public record SuccessfulBenefitResult(
    String sourceSettlementId,
    String sourceAllocationId,
    String sourceFundingLotId,
    String sourceWalletOwnerUserId,
    long referralBindingVersion,
    Optional<PointRewardAction> pointReward,
    Optional<CashReleaseAction> cashRelease) {

  public SuccessfulBenefitResult {
    sourceSettlementId = PromotionIdentifiers.requireId(sourceSettlementId, "sourceSettlementId");
    sourceAllocationId = PromotionIdentifiers.requireId(sourceAllocationId, "sourceAllocationId");
    sourceFundingLotId = PromotionIdentifiers.requireId(sourceFundingLotId, "sourceFundingLotId");
    sourceWalletOwnerUserId =
        PromotionIdentifiers.requireId(sourceWalletOwnerUserId, "sourceWalletOwnerUserId");
    if (referralBindingVersion < 1) {
      throw new IllegalArgumentException("referralBindingVersion must be positive");
    }
    pointReward = Objects.requireNonNull(pointReward, "pointReward");
    cashRelease = Objects.requireNonNull(cashRelease, "cashRelease");
    if ((pointReward.isPresent()
            && sourceWalletOwnerUserId.equals(
                pointReward.orElseThrow().beneficiaryUserId()))
        || (cashRelease.isPresent()
            && sourceWalletOwnerUserId.equals(
                cashRelease.orElseThrow().beneficiaryPromoterUserId()))) {
      throw new IllegalArgumentException("a frozen benefit cannot pay the source wallet owner");
    }
  }
}
