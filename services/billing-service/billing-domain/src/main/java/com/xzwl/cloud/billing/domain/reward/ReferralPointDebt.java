package com.xzwl.cloud.billing.domain.reward;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;

public record ReferralPointDebt(String beneficiaryUserId, Decimal4 amount4) {

  public ReferralPointDebt {
    ReferralRewardLot.requireId(beneficiaryUserId, "beneficiaryUserId");
    Objects.requireNonNull(amount4, "amount4");
    if (amount4.isNegative()) {
      throw new IllegalArgumentException("referral point debt cannot be negative");
    }
  }

  public static ReferralPointDebt zero(String beneficiaryUserId) {
    return new ReferralPointDebt(beneficiaryUserId, Decimal4.zero());
  }

  ReferralPointDebt withAmount4(Decimal4 amount) {
    return new ReferralPointDebt(beneficiaryUserId, amount);
  }
}
