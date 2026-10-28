package com.xzwl.cloud.promotion.application.withdrawal;

import java.time.Instant;

/** Application input for approving a withdrawal. */
public record ApproveWithdrawalCommand(
    String withdrawalId, String commandId, String operatorId, Instant changedAt) {}
