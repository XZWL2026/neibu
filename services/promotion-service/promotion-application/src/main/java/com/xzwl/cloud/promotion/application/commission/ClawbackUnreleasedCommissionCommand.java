package com.xzwl.cloud.promotion.application.commission;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.time.Instant;

/** Application input for clawing back unreleased commission after a recharge refund. */
public record ClawbackUnreleasedCommissionCommand(
    String promoterUserId,
    String sourceEventId,
    String orderId,
    String fundingLotId,
    Decimal4 amount4,
    Instant createdAt) {}
