package com.xzwl.cloud.billing.domain.reward;

import com.xzwl.cloud.billing.domain.funding.FundingLotType;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.time.Instant;
import java.util.Objects;

public record ReferralRewardLot(
    String rewardEntitlementId,
    String beneficiaryUserId,
    String lotId,
    Decimal4 amount4,
    Decimal4 remaining4,
    Instant creditedAt) {

  public ReferralRewardLot {
    requireId(rewardEntitlementId, "rewardEntitlementId");
    requireId(beneficiaryUserId, "beneficiaryUserId");
    requireId(lotId, "lotId");
    Objects.requireNonNull(amount4, "amount4");
    Objects.requireNonNull(remaining4, "remaining4");
    Objects.requireNonNull(creditedAt, "creditedAt");
    if (!amount4.isPositive()) {
      throw new IllegalArgumentException("amount4 must be positive");
    }
    if (remaining4.isNegative() || remaining4.compareTo(amount4) > 0) {
      throw new IllegalArgumentException("remaining4 must be between zero and amount4");
    }
  }

  public FundingLotType fundingLotType() {
    return FundingLotType.REFERRAL_REWARD;
  }

  public boolean benefitEligible() {
    return false;
  }

  ReferralRewardLot withRemaining4(Decimal4 remaining) {
    return new ReferralRewardLot(
        rewardEntitlementId, beneficiaryUserId, lotId, amount4, remaining, creditedAt);
  }

  static String requireId(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
