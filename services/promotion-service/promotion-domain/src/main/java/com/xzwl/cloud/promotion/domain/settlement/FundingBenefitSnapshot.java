package com.xzwl.cloud.promotion.domain.settlement;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.money.Rate6;
import com.xzwl.cloud.promotion.domain.referral.BenefitSnapshot;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.Objects;

/** Immutable benefit snapshot persisted with one funding lot. */
public record FundingBenefitSnapshot(
    String orderId,
    String fundingLotId,
    String walletOwnerUserId,
    Instant orderCreatedAt,
    Long referralBindingVersion,
    String pointBeneficiaryUserId,
    String cashPromoterUserId,
    CashTier cashTier,
    Rate6 cashRate6,
    Rate6 pointRate6,
    Decimal4 frozenCommission4,
    boolean benefitEligible) {

  public FundingBenefitSnapshot {
    orderId = PromotionIdentifiers.requireId(orderId, "orderId");
    fundingLotId = PromotionIdentifiers.requireId(fundingLotId, "fundingLotId");
    walletOwnerUserId = PromotionIdentifiers.requireId(walletOwnerUserId, "walletOwnerUserId");
    orderCreatedAt = Objects.requireNonNull(orderCreatedAt, "orderCreatedAt");
    pointBeneficiaryUserId =
        PromotionIdentifiers.optionalId(pointBeneficiaryUserId, "pointBeneficiaryUserId");
    cashPromoterUserId =
        PromotionIdentifiers.optionalId(cashPromoterUserId, "cashPromoterUserId");
    cashTier = Objects.requireNonNull(cashTier, "cashTier");
    cashRate6 = Objects.requireNonNull(cashRate6, "cashRate6");
    pointRate6 = Objects.requireNonNull(pointRate6, "pointRate6");
    frozenCommission4 = Objects.requireNonNull(frozenCommission4, "frozenCommission4");

    BenefitSnapshot benefits = snapshot(pointBeneficiaryUserId, cashPromoterUserId, cashTier);
    RechargeBenefitInput.validateRates(benefits, cashRate6, pointRate6);
    RechargeBenefitInput.validateIdentities(walletOwnerUserId, benefits);
    RechargeBenefitInput.validateEligibility(
        referralBindingVersion, benefits, cashRate6, pointRate6, benefitEligible);
    if (frozenCommission4.compareTo(Decimal4.ZERO) < 0) {
      throw new IllegalArgumentException("frozenCommission4 must not be negative");
    }
    if (cashPromoterUserId == null && !frozenCommission4.equals(Decimal4.ZERO)) {
      throw new IllegalArgumentException("frozen commission requires a cash promoter");
    }
    if (cashRate6.equals(Rate6.ZERO) && !frozenCommission4.equals(Decimal4.ZERO)) {
      throw new IllegalArgumentException("zero cash rate requires zero frozen commission");
    }
  }

  private static BenefitSnapshot snapshot(
      String pointBeneficiaryUserId, String cashPromoterUserId, CashTier cashTier) {
    return switch (cashTier) {
      case LEVEL_1 -> {
        if (pointBeneficiaryUserId != null) {
          throw new IllegalArgumentException("LEVEL_1 cannot have a point beneficiary");
        }
        yield BenefitSnapshot.cashOnly(cashPromoterUserId);
      }
      case LEVEL_2 -> BenefitSnapshot.pointsAndCash(pointBeneficiaryUserId, cashPromoterUserId);
      case NONE -> {
        if (cashPromoterUserId != null) {
          throw new IllegalArgumentException("NONE cannot have a cash promoter");
        }
        yield pointBeneficiaryUserId == null
            ? BenefitSnapshot.none()
            : BenefitSnapshot.pointsOnly(pointBeneficiaryUserId);
      }
    };
  }
}
