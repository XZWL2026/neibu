package com.xzwl.cloud.promotion.application.commission;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.time.Instant;

/** Application input for releasing frozen commission after a successful allocation. */
public record ReleaseCommissionCommand(
    String promoterUserId,
    String sourceEventId,
    String orderId,
    String fundingLotId,
    String allocationId,
    Decimal4 amount4,
    Instant createdAt) {}
