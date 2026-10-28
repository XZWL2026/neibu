package com.xzwl.cloud.promotion.domain.settlement;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.money.Rate6;
import com.xzwl.cloud.promotion.domain.referral.BenefitSnapshot;
import java.util.Objects;

/** Immutable promotion inputs captured by one successful funding allocation. */
public record SuccessfulBenefitInput(
    String sourceSettlementId,
    String sourceAllocationId,
    String sourceFundingLotId,
    String sourceWalletOwnerUserId,
    long referralBindingVersion,
    BenefitSnapshot benefitSnapshot,
    Rate6 pointRate6,
    Decimal4 lotOriginalPoints4,
    Decimal4 successfulPoints4,
    Decimal4 frozenCommission4,
    Decimal4 releasedCommissionBefore4,
    boolean finalDepletion,
    boolean benefitEligible) {

  public SuccessfulBenefitInput {
    sourceSettlementId = PromotionIdentifiers.requireId(sourceSettlementId, "sourceSettlementId");
    sourceAllocationId = PromotionIdentifiers.requireId(sourceAllocationId, "sourceAllocationId");
    sourceFundingLotId = PromotionIdentifiers.requireId(sourceFundingLotId, "sourceFundingLotId");
    sourceWalletOwnerUserId =
        PromotionIdentifiers.requireId(sourceWalletOwnerUserId, "sourceWalletOwnerUserId");
    if (referralBindingVersion < 1) {
      throw new IllegalArgumentException("referralBindingVersion must be positive");
    }
    benefitSnapshot = Objects.requireNonNull(benefitSnapshot, "benefitSnapshot");
    pointRate6 = Objects.requireNonNull(pointRate6, "pointRate6");
    lotOriginalPoints4 = Objects.requireNonNull(lotOriginalPoints4, "lotOriginalPoints4");
    successfulPoints4 = Objects.requireNonNull(successfulPoints4, "successfulPoints4");
    frozenCommission4 = Objects.requireNonNull(frozenCommission4, "frozenCommission4");
    releasedCommissionBefore4 =
        Objects.requireNonNull(releasedCommissionBefore4, "releasedCommissionBefore4");

    requirePositive(lotOriginalPoints4, "lotOriginalPoints4");
    requirePositive(successfulPoints4, "successfulPoints4");
    if (successfulPoints4.compareTo(lotOriginalPoints4) > 0) {
      throw new IllegalArgumentException("successfulPoints4 cannot exceed lotOriginalPoints4");
    }
    requireNonNegative(frozenCommission4, "frozenCommission4");
    requireNonNegative(releasedCommissionBefore4, "releasedCommissionBefore4");
    if (releasedCommissionBefore4.compareTo(frozenCommission4) > 0) {
      throw new IllegalArgumentException(
          "releasedCommissionBefore4 cannot exceed frozenCommission4");
    }
    if (benefitSnapshot.pointBeneficiaryUserId() == null && !pointRate6.equals(Rate6.ZERO)) {
      throw new IllegalArgumentException("pointRate6 must be zero without a point beneficiary");
    }
    if (sourceWalletOwnerUserId.equals(benefitSnapshot.pointBeneficiaryUserId())
        || sourceWalletOwnerUserId.equals(benefitSnapshot.cashPromoterUserId())) {
      throw new IllegalArgumentException("a frozen benefit cannot pay the source wallet owner");
    }
    if (benefitSnapshot.cashPromoterUserId() == null
        && (!frozenCommission4.equals(Decimal4.ZERO)
            || !releasedCommissionBefore4.equals(Decimal4.ZERO))) {
      throw new IllegalArgumentException("cash amounts require a frozen cash promoter");
    }
  }

  private static void requirePositive(Decimal4 amount, String name) {
    if (amount.compareTo(Decimal4.ZERO) <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireNonNegative(Decimal4 amount, String name) {
    if (amount.compareTo(Decimal4.ZERO) < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
