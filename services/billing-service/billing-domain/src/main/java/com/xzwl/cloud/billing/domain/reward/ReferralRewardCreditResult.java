package com.xzwl.cloud.billing.domain.reward;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;
import java.util.Optional;

public record ReferralRewardCreditResult(
    Decimal4 offsetDebt4,
    Decimal4 creditableReward4,
    ReferralPointDebt updatedDebt,
    Optional<ReferralRewardLot> createdLot) {

  public ReferralRewardCreditResult {
    Objects.requireNonNull(offsetDebt4, "offsetDebt4");
    Objects.requireNonNull(creditableReward4, "creditableReward4");
    Objects.requireNonNull(updatedDebt, "updatedDebt");
    Objects.requireNonNull(createdLot, "createdLot");
    if (offsetDebt4.isNegative() || creditableReward4.isNegative()) {
      throw new IllegalArgumentException("reward credit amounts cannot be negative");
    }
  }
}
