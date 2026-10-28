package com.xzwl.cloud.promotion.domain.settlement;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.util.Objects;
import java.util.Optional;

/** Freezes recharge benefits without consulting live referral or policy state. */
public final class RechargeBenefitCalculator {

  private RechargeBenefitCalculator() {}

  public static RechargeBenefitResult calculate(RechargeBenefitInput input) {
    Objects.requireNonNull(input, "input");
    Decimal4 frozenCommission = input.paidAmount4().multiply(input.cashRate6());
    FundingBenefitSnapshot snapshot = new FundingBenefitSnapshot(
        input.orderId(),
        input.fundingLotId(),
        input.walletOwnerUserId(),
        input.orderCreatedAt(),
        input.referralBindingVersion(),
        input.benefitSnapshot().pointBeneficiaryUserId(),
        input.benefitSnapshot().cashPromoterUserId(),
        input.benefitSnapshot().cashTier(),
        input.cashRate6(),
        input.pointRate6(),
        frozenCommission,
        input.benefitEligible());
    Optional<CommissionFreezeAction> freeze = frozenCommission.compareTo(Decimal4.ZERO) > 0
        ? Optional.of(new CommissionFreezeAction(
            input.sourceEventId(),
            input.orderId(),
            input.fundingLotId(),
            input.benefitSnapshot().cashPromoterUserId(),
            input.referralBindingVersion(),
            input.benefitSnapshot().cashTier(),
            frozenCommission))
        : Optional.empty();
    return new RechargeBenefitResult(snapshot, freeze);
  }
}
