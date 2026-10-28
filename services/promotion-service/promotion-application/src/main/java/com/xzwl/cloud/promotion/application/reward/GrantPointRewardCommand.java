package com.xzwl.cloud.promotion.application.reward;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;

/** Internal command carrying the frozen source snapshot for a direct point reward. */
public record GrantPointRewardCommand(
    String beneficiaryUserId,
    String sourceEventId,
    String rewardEntitlementId,
    String sourceSettlementId,
    String sourceAllocationId,
    String sourceFundingLotId,
    String sourceWalletOwnerUserId,
    long referralBindingVersion,
    CashTier cashTier,
    Decimal4 points,
    Instant createdAt) {}
