package com.xzwl.cloud.promotion.application.commission;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.time.Instant;

/** Application input for reversing commission released for a completed allocation. */
public record ReverseCommissionReleaseCommand(
    String promoterUserId,
    String sourceEventId,
    String allocationId,
    Decimal4 amount4,
    Instant createdAt) {}
