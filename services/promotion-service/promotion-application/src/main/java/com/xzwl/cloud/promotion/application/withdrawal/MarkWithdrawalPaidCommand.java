package com.xzwl.cloud.promotion.application.withdrawal;

import java.time.Instant;

/** Application input for confirming an offline withdrawal payout. */
public record MarkWithdrawalPaidCommand(
    String withdrawalId, String commandId, String operatorId, Instant changedAt) {}
