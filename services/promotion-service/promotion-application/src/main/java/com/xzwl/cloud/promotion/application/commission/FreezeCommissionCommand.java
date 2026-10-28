package com.xzwl.cloud.promotion.application.commission;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;

/** Application input for freezing commission on a credited recharge lot. */
public record FreezeCommissionCommand(
    String promoterUserId,
    String sourceEventId,
    String orderId,
    String fundingLotId,
    Decimal4 amount4,
    long referralBindingVersion,
    CashTier cashTier,
    Instant createdAt) {}
