package com.xzwl.cloud.promotion.domain.settlement;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.util.Objects;
import java.util.Optional;

/** Calculates benefit actions without consulting live referral or policy state. */
public final class SuccessfulBenefitCalculator {

  private SuccessfulBenefitCalculator() {}

  public static SuccessfulBenefitResult calculate(SuccessfulBenefitInput input) {
    Objects.requireNonNull(input, "input");

    Optional<PointRewardAction> pointReward = Optional.empty();
    Optional<CashReleaseAction> cashRelease = Optional.empty();
    if (input.benefitEligible()) {
      pointReward = calculatePointReward(input);
      cashRelease = calculateCashRelease(input);
    }
    return new SuccessfulBenefitResult(
        input.sourceSettlementId(),
        input.sourceAllocationId(),
        input.sourceFundingLotId(),
        input.sourceWalletOwnerUserId(),
        input.referralBindingVersion(),
        pointReward,
        cashRelease);
  }

  private static Optional<PointRewardAction> calculatePointReward(SuccessfulBenefitInput input) {
    String beneficiary = input.benefitSnapshot().pointBeneficiaryUserId();
    Decimal4 points = input.successfulPoints4().multiply(input.pointRate6());
    if (beneficiary == null || points.compareTo(Decimal4.ZERO) <= 0) {
      return Optional.empty();
    }
    return Optional.of(new PointRewardAction(beneficiary, points));
  }

  private static Optional<CashReleaseAction> calculateCashRelease(SuccessfulBenefitInput input) {
    String promoter = input.benefitSnapshot().cashPromoterUserId();
    if (promoter == null) {
      return Optional.empty();
    }

    Decimal4 remaining = input.frozenCommission4().subtract(input.releasedCommissionBefore4());
    Decimal4 release = input.finalDepletion()
        ? remaining
        : input.frozenCommission4()
            .multiplyThenDivide(input.successfulPoints4(), input.lotOriginalPoints4());
    if (release.compareTo(remaining) > 0) {
      throw new IllegalArgumentException("cash release cannot exceed the frozen remaining amount");
    }
    if (release.compareTo(Decimal4.ZERO) <= 0) {
      return Optional.empty();
    }
    return Optional.of(
        new CashReleaseAction(promoter, input.benefitSnapshot().cashTier(), release));
  }
}
