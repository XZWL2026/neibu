package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.time.Instant;

/** Application input for requesting a withdrawal. */
public record WithdrawalCommand(
    String promoterId,
    String withdrawalId,
    String amountInput,
    Decimal4 minimumAmount,
    String payoutAccountId,
    String commandId,
    Instant requestedAt) {}
