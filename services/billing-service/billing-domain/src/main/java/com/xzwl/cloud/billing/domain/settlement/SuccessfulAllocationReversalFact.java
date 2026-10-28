package com.xzwl.cloud.billing.domain.settlement;

import com.xzwl.cloud.billing.domain.funding.FundingLotType;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.util.Objects;

public record SuccessfulAllocationReversalFact(
    String reversalId,
    String settlementId,
    String taskId,
    String allocationId,
    String lotId,
    String walletId,
    FundingLotType lotType,
    boolean benefitEligible,
    Decimal4 reversedPoints4,
    boolean publishPromotionBenefitReversal) {

  public SuccessfulAllocationReversalFact {
    TaskSettlement.requireId(reversalId, "reversalId");
    TaskSettlement.requireId(settlementId, "settlementId");
    TaskSettlement.requireId(taskId, "taskId");
    TaskSettlement.requireId(allocationId, "allocationId");
    TaskSettlement.requireId(lotId, "lotId");
    TaskSettlement.requireId(walletId, "walletId");
    Objects.requireNonNull(lotType, "lotType");
    Objects.requireNonNull(reversedPoints4, "reversedPoints4");
    if (!reversedPoints4.isPositive()) {
      throw new IllegalArgumentException("reversedPoints4 must be positive");
    }
    if (lotType != FundingLotType.REAL_RECHARGE && benefitEligible) {
      throw new IllegalArgumentException("gift and referral reward reversals cannot be benefit eligible");
    }
    if (publishPromotionBenefitReversal
        && (lotType != FundingLotType.REAL_RECHARGE || !benefitEligible)) {
      throw new IllegalArgumentException(
          "only benefit eligible real recharge reversals can publish promotion benefits");
    }
  }
}
