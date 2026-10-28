package com.xzwl.cloud.promotion.domain.reward;

import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.util.Objects;

/** Immutable identity of the frozen task-success reward allocation. */
public record RewardSourceSnapshot(
    String rewardEntitlementId,
    String sourceSettlementId,
    String sourceAllocationId,
    String sourceFundingLotId,
    String sourceWalletOwnerUserId,
    long referralBindingVersion,
    CashTier cashTier) {

  public RewardSourceSnapshot {
    rewardEntitlementId =
        PointRewardAmounts.requireId(rewardEntitlementId, "rewardEntitlementId");
    sourceSettlementId =
        PointRewardAmounts.requireId(sourceSettlementId, "sourceSettlementId");
    sourceAllocationId =
        PointRewardAmounts.requireId(sourceAllocationId, "sourceAllocationId");
    sourceFundingLotId =
        PointRewardAmounts.requireId(sourceFundingLotId, "sourceFundingLotId");
    sourceWalletOwnerUserId =
        PointRewardAmounts.requireId(sourceWalletOwnerUserId, "sourceWalletOwnerUserId");
    if (referralBindingVersion < 1) {
      throw new IllegalArgumentException("referralBindingVersion must be positive");
    }
    cashTier = Objects.requireNonNull(cashTier, "cashTier");
  }

  @Override
  public String toString() {
    return "RewardSourceSnapshot[referralBindingVersion=%d, cashTier=%s]"
        .formatted(referralBindingVersion, cashTier);
  }
}
