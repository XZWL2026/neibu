package com.xzwl.cloud.billing.domain.funding;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.time.Instant;
import java.util.Objects;

public record FundingLot(
    String id,
    String walletId,
    FundingLotType type,
    Decimal4 originalPoints4,
    Decimal4 remainingPoints4,
    Instant creditedAt,
    boolean benefitEligible) {

  public FundingLot {
    requireId(id, "id");
    requireId(walletId, "walletId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(originalPoints4, "originalPoints4");
    Objects.requireNonNull(remainingPoints4, "remainingPoints4");
    Objects.requireNonNull(creditedAt, "creditedAt");
    if (originalPoints4.isNegative()
        || remainingPoints4.isNegative()
        || remainingPoints4.compareTo(originalPoints4) > 0) {
      throw new IllegalArgumentException("remainingPoints4 must be between zero and originalPoints4");
    }
    if (type != FundingLotType.REAL_RECHARGE && benefitEligible) {
      throw new IllegalArgumentException("gift and referral reward lots cannot produce benefits");
    }
  }

  FundingLot withRemainingPoints4(Decimal4 remaining) {
    return new FundingLot(id, walletId, type, originalPoints4, remaining, creditedAt, benefitEligible);
  }

  static String requireId(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
