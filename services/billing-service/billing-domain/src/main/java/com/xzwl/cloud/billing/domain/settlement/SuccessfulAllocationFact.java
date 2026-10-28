package com.xzwl.cloud.billing.domain.settlement;

import com.xzwl.cloud.billing.domain.funding.FundingLotType;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;

public record SuccessfulAllocationFact(
    String settlementId,
    String taskId,
    String allocationId,
    String lotId,
    String walletId,
    FundingLotType lotType,
    boolean benefitEligible,
    Decimal4 successfulPoints4,
    boolean publishPromotionBenefit) {

  public SuccessfulAllocationFact {
    TaskSettlement.requireId(settlementId, "settlementId");
    TaskSettlement.requireId(taskId, "taskId");
    TaskSettlement.requireId(allocationId, "allocationId");
    TaskSettlement.requireId(lotId, "lotId");
    TaskSettlement.requireId(walletId, "walletId");
    Objects.requireNonNull(lotType, "lotType");
    Objects.requireNonNull(successfulPoints4, "successfulPoints4");
    if (!successfulPoints4.isPositive()) {
      throw new IllegalArgumentException("successfulPoints4 must be positive");
    }
    if (lotType != FundingLotType.REAL_RECHARGE && benefitEligible) {
      throw new IllegalArgumentException("gift and referral reward facts cannot be benefit eligible");
    }
    if (publishPromotionBenefit && !benefitEligible) {
      throw new IllegalArgumentException("only benefit eligible facts can publish promotion benefits");
    }
  }
}
