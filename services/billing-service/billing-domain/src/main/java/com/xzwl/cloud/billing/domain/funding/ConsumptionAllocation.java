package com.xzwl.cloud.billing.domain.funding;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;

public record ConsumptionAllocation(
    String operationId,
    String lotId,
    String walletId,
    Decimal4 allocatedPoints4,
    FundingLotType lotType,
    boolean benefitEligible) {

  public ConsumptionAllocation {
    FundingLot.requireId(operationId, "operationId");
    FundingLot.requireId(lotId, "lotId");
    FundingLot.requireId(walletId, "walletId");
    Objects.requireNonNull(allocatedPoints4, "allocatedPoints4");
    Objects.requireNonNull(lotType, "lotType");
    if (!allocatedPoints4.isPositive()) {
      throw new IllegalArgumentException("allocatedPoints4 must be positive");
    }
    if (lotType != FundingLotType.REAL_RECHARGE && benefitEligible) {
      throw new IllegalArgumentException("gift and referral reward allocations cannot produce benefits");
    }
  }
}
