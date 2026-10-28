package com.xzwl.cloud.promotion.application.withdrawal;

import java.time.Instant;

/** Application input for rejecting a withdrawal. */
public record RejectWithdrawalCommand(
    String withdrawalId, String commandId, String operatorId, String reason, Instant changedAt) {}
