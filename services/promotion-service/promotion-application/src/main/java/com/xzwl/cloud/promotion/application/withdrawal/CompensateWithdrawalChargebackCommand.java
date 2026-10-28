package com.xzwl.cloud.promotion.application.withdrawal;

import java.time.Instant;

/** Application input for compensating a withdrawal after an original-order chargeback. */
public record CompensateWithdrawalChargebackCommand(
    String withdrawalId, String commandId, String sourceOrderId, Instant changedAt) {}
