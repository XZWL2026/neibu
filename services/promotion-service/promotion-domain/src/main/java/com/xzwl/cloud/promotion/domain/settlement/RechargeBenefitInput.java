package com.xzwl.cloud.promotion.domain.settlement;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.money.Rate6;
import com.xzwl.cloud.promotion.domain.referral.BenefitSnapshot;
import java.time.Instant;
import java.util.Objects;

/** Immutable order-time facts used to freeze benefits on one credited funding lot. */
public record RechargeBenefitInput(
    String sourceEventId,
    String orderId,
    String fundingLotId,
    String walletOwnerUserId,
    Instant orderCreatedAt,
    Long referralBindingVersion,
    BenefitSnapshot benefitSnapshot,
    Decimal4 paidAmount4,
    Decimal4 creditedPoints4,
    Rate6 cashRate6,
    Rate6 pointRate6,
    boolean benefitEligible) {

  public RechargeBenefitInput {
    sourceEventId = PromotionIdentifiers.requireId(sourceEventId, "sourceEventId");
    orderId = PromotionIdentifiers.requireId(orderId, "orderId");
    fundingLotId = PromotionIdentifiers.requireId(fundingLotId, "fundingLotId");
    walletOwnerUserId = PromotionIdentifiers.requireId(walletOwnerUserId, "walletOwnerUserId");
    orderCreatedAt = Objects.requireNonNull(orderCreatedAt, "orderCreatedAt");
    benefitSnapshot = Objects.requireNonNull(benefitSnapshot, "benefitSnapshot");
    paidAmount4 = Objects.requireNonNull(paidAmount4, "paidAmount4");
    creditedPoints4 = Objects.requireNonNull(creditedPoints4, "creditedPoints4");
    cashRate6 = Objects.requireNonNull(cashRate6, "cashRate6");
    pointRate6 = Objects.requireNonNull(pointRate6, "pointRate6");

    requirePositive(paidAmount4, "paidAmount4");
    requirePositive(creditedPoints4, "creditedPoints4");
    validateIdentities(walletOwnerUserId, benefitSnapshot);
    validateEligibility(
        referralBindingVersion, benefitSnapshot, cashRate6, pointRate6, benefitEligible);
    validateRates(benefitSnapshot, cashRate6, pointRate6);
  }

  static void validateIdentities(
      String walletOwnerUserId, BenefitSnapshot benefitSnapshot) {
    if (walletOwnerUserId.equals(benefitSnapshot.pointBeneficiaryUserId())
        || walletOwnerUserId.equals(benefitSnapshot.cashPromoterUserId())) {
      throw new IllegalArgumentException("a frozen benefit cannot pay the wallet owner");
    }
  }

  static void validateRates(
      BenefitSnapshot benefitSnapshot, Rate6 cashRate6, Rate6 pointRate6) {
    if (benefitSnapshot.pointBeneficiaryUserId() == null && !pointRate6.equals(Rate6.ZERO)) {
      throw new IllegalArgumentException("pointRate6 must be zero without a point beneficiary");
    }
    if (benefitSnapshot.cashPromoterUserId() == null && !cashRate6.equals(Rate6.ZERO)) {
      throw new IllegalArgumentException("cashRate6 must be zero without a cash promoter");
    }
  }

  static void validateEligibility(
      Long referralBindingVersion,
      BenefitSnapshot benefitSnapshot,
      Rate6 cashRate6,
      Rate6 pointRate6,
      boolean benefitEligible) {
    if (benefitEligible) {
      if (referralBindingVersion == null || referralBindingVersion < 1) {
        throw new IllegalArgumentException("referralBindingVersion must be positive");
      }
      if (benefitSnapshot.equals(BenefitSnapshot.none())) {
        throw new IllegalArgumentException("eligible recharge requires a benefit identity");
      }
      return;
    }
    if (referralBindingVersion != null
        || !benefitSnapshot.equals(BenefitSnapshot.none())
        || !cashRate6.equals(Rate6.ZERO)
        || !pointRate6.equals(Rate6.ZERO)) {
      throw new IllegalArgumentException(
          "ineligible recharge requires NONE, zero rates, and no binding version");
    }
  }

  private static void requirePositive(Decimal4 amount, String name) {
    if (amount.compareTo(Decimal4.ZERO) <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
