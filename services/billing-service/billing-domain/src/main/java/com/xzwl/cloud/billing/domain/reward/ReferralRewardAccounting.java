package com.xzwl.cloud.billing.domain.reward;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class ReferralRewardAccounting {

  private ReferralRewardAccounting() {}

  public static ReferralRewardReversalResult reverse(
      ReferralRewardLot lot, ReferralPointDebt currentDebt, Decimal4 reversalAmount4) {
    Objects.requireNonNull(lot, "lot");
    Objects.requireNonNull(currentDebt, "currentDebt");
    Objects.requireNonNull(reversalAmount4, "reversalAmount4");
    requireSameBeneficiary(lot.beneficiaryUserId(), currentDebt.beneficiaryUserId());
    if (!reversalAmount4.isPositive()) {
      throw new IllegalArgumentException("reversalAmount4 must be positive");
    }
    if (reversalAmount4.compareTo(lot.amount4()) > 0) {
      throw new IllegalArgumentException("reversalAmount4 cannot exceed the original reward");
    }

    Decimal4 recovered =
        reversalAmount4.compareTo(lot.remaining4()) < 0 ? reversalAmount4 : lot.remaining4();
    Decimal4 createdDebt = reversalAmount4.subtract(recovered);
    return new ReferralRewardReversalResult(
        recovered,
        createdDebt,
        lot.withRemaining4(lot.remaining4().subtract(recovered)),
        currentDebt.withAmount4(currentDebt.amount4().add(createdDebt)));
  }

  public static ReferralRewardCreditResult creditReferralReward(
      String rewardEntitlementId,
      String beneficiaryUserId,
      String lotId,
      Decimal4 rewardAmount4,
      Instant creditedAt,
      ReferralPointDebt currentDebt) {
    ReferralRewardLot.requireId(rewardEntitlementId, "rewardEntitlementId");
    ReferralRewardLot.requireId(beneficiaryUserId, "beneficiaryUserId");
    ReferralRewardLot.requireId(lotId, "lotId");
    Objects.requireNonNull(rewardAmount4, "rewardAmount4");
    Objects.requireNonNull(creditedAt, "creditedAt");
    Objects.requireNonNull(currentDebt, "currentDebt");
    requireSameBeneficiary(beneficiaryUserId, currentDebt.beneficiaryUserId());
    if (!rewardAmount4.isPositive()) {
      throw new IllegalArgumentException("rewardAmount4 must be positive");
    }

    Decimal4 offsetDebt =
        rewardAmount4.compareTo(currentDebt.amount4()) < 0
            ? rewardAmount4
            : currentDebt.amount4();
    Decimal4 creditableReward = rewardAmount4.subtract(offsetDebt);
    Optional<ReferralRewardLot> createdLot =
        creditableReward.isPositive()
            ? Optional.of(
                new ReferralRewardLot(
                    rewardEntitlementId,
                    beneficiaryUserId,
                    lotId,
                    creditableReward,
                    creditableReward,
                    creditedAt))
            : Optional.empty();
    return new ReferralRewardCreditResult(
        offsetDebt,
        creditableReward,
        currentDebt.withAmount4(currentDebt.amount4().subtract(offsetDebt)),
        createdLot);
  }

  private static void requireSameBeneficiary(String expected, String actual) {
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("referral reward and debt must belong to the same beneficiary");
    }
  }
}
