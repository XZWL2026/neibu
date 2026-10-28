package com.xzwl.cloud.billing.application.funding;

import com.xzwl.cloud.billing.domain.money.Decimal4;

/** Application input for consuming wallet funding lots. */
public record ConsumeFundingCommand(String walletId, String operationId, Decimal4 requestedPoints4) {}
