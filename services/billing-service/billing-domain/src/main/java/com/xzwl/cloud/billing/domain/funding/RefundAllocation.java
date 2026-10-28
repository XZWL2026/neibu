package com.xzwl.cloud.billing.domain.funding;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;

public record RefundAllocation(
    String refundId,
    String operationId,
    String lotId,
    String walletId,
    Decimal4 refundedPoints4,
    FundingLotType lotType,
    boolean benefitEligible) {

  public RefundAllocation {
    FundingLot.requireId(refundId, "refundId");
    FundingLot.requireId(operationId, "operationId");
    FundingLot.requireId(lotId, "lotId");
    FundingLot.requireId(walletId, "walletId");
    Objects.requireNonNull(refundedPoints4, "refundedPoints4");
    Objects.requireNonNull(lotType, "lotType");
    if (!refundedPoints4.isPositive()) {
      throw new IllegalArgumentException("refundedPoints4 must be positive");
    }
    if (lotType != FundingLotType.REAL_RECHARGE && benefitEligible) {
      throw new IllegalArgumentException("gift and referral reward refunds cannot produce benefits");
    }
  }
}
