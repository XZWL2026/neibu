package com.xzwl.cloud.billing.domain.reward;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;

public record ReferralRewardReversalResult(
    Decimal4 recovered4,
    Decimal4 createdDebt4,
    ReferralRewardLot updatedLot,
    ReferralPointDebt updatedDebt) {

  public ReferralRewardReversalResult {
    Objects.requireNonNull(recovered4, "recovered4");
    Objects.requireNonNull(createdDebt4, "createdDebt4");
    Objects.requireNonNull(updatedLot, "updatedLot");
    Objects.requireNonNull(updatedDebt, "updatedDebt");
    if (recovered4.isNegative() || createdDebt4.isNegative()) {
      throw new IllegalArgumentException("reversal amounts cannot be negative");
    }
  }
}
