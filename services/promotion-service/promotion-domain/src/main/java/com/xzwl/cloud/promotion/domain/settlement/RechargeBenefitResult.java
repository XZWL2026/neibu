package com.xzwl.cloud.promotion.domain.settlement;

import java.util.Objects;
import java.util.Optional;

/** Immutable recharge-time funding snapshot and optional cash freeze action. */
public record RechargeBenefitResult(
    FundingBenefitSnapshot fundingBenefitSnapshot,
    Optional<CommissionFreezeAction> commissionFreeze) {

  public RechargeBenefitResult {
    fundingBenefitSnapshot =
        Objects.requireNonNull(fundingBenefitSnapshot, "fundingBenefitSnapshot");
    commissionFreeze = Objects.requireNonNull(commissionFreeze, "commissionFreeze");
    if (commissionFreeze.isPresent()) {
      CommissionFreezeAction action = commissionFreeze.orElseThrow();
      if (!action.orderId().equals(fundingBenefitSnapshot.orderId())
          || !action.fundingLotId().equals(fundingBenefitSnapshot.fundingLotId())
          || !action.beneficiaryPromoterUserId()
              .equals(fundingBenefitSnapshot.cashPromoterUserId())
          || action.referralBindingVersion() != fundingBenefitSnapshot.referralBindingVersion()
          || action.cashTier() != fundingBenefitSnapshot.cashTier()
          || !action.amount4().equals(fundingBenefitSnapshot.frozenCommission4())) {
        throw new IllegalArgumentException("commission freeze does not match funding snapshot");
      }
    } else if (fundingBenefitSnapshot.frozenCommission4()
        .compareTo(com.xzwl.cloud.promotion.domain.money.Decimal4.ZERO) > 0) {
      throw new IllegalArgumentException("positive frozen commission requires a freeze action");
    }
  }
}
