package com.xzwl.cloud.promotion.domain.referral;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import java.util.Objects;

public final class BenefitSnapshot {

  private final String pointBeneficiaryUserId;
  private final String cashPromoterUserId;
  private final CashTier cashTier;

  private BenefitSnapshot(
      String pointBeneficiaryUserId,
      String cashPromoterUserId,
      CashTier cashTier) {
    this.pointBeneficiaryUserId = PromotionIdentifiers.optionalId(
        pointBeneficiaryUserId, "pointBeneficiaryUserId");
    this.cashPromoterUserId =
        PromotionIdentifiers.optionalId(cashPromoterUserId, "cashPromoterUserId");
    this.cashTier = Objects.requireNonNull(cashTier, "cashTier");
    validateCombination();
  }

  public static BenefitSnapshot none() {
    return new BenefitSnapshot(null, null, CashTier.NONE);
  }

  public static BenefitSnapshot cashOnly(String cashPromoterUserId) {
    return new BenefitSnapshot(
        null,
        PromotionIdentifiers.requireId(cashPromoterUserId, "cashPromoterUserId"),
        CashTier.LEVEL_1);
  }

  public static BenefitSnapshot pointsAndCash(
      String pointBeneficiaryUserId,
      String cashPromoterUserId) {
    return new BenefitSnapshot(
        PromotionIdentifiers.requireId(pointBeneficiaryUserId, "pointBeneficiaryUserId"),
        PromotionIdentifiers.requireId(cashPromoterUserId, "cashPromoterUserId"),
        CashTier.LEVEL_2);
  }

  public static BenefitSnapshot pointsOnly(String pointBeneficiaryUserId) {
    return new BenefitSnapshot(
        PromotionIdentifiers.requireId(pointBeneficiaryUserId, "pointBeneficiaryUserId"),
        null,
        CashTier.NONE);
  }

  public String pointBeneficiaryUserId() {
    return pointBeneficiaryUserId;
  }

  public String cashPromoterUserId() {
    return cashPromoterUserId;
  }

  public CashTier cashTier() {
    return cashTier;
  }

  private void validateCombination() {
    if (cashTier == CashTier.NONE && cashPromoterUserId != null) {
      throw new IllegalArgumentException("NONE cannot have a cash promoter");
    }
    if (cashTier != CashTier.NONE && cashPromoterUserId == null) {
      throw new IllegalArgumentException("cash tier requires a cash promoter");
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof BenefitSnapshot that)) {
      return false;
    }
    return Objects.equals(pointBeneficiaryUserId, that.pointBeneficiaryUserId)
        && Objects.equals(cashPromoterUserId, that.cashPromoterUserId)
        && cashTier == that.cashTier;
  }

  @Override
  public int hashCode() {
    return Objects.hash(pointBeneficiaryUserId, cashPromoterUserId, cashTier);
  }

  @Override
  public String toString() {
    return "BenefitSnapshot[pointBeneficiaryPresent=%s, cashPromoterPresent=%s, cashTier=%s]"
        .formatted(pointBeneficiaryUserId != null, cashPromoterUserId != null, cashTier);
  }
}
