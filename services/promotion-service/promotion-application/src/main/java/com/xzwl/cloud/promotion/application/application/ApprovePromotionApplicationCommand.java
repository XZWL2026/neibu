package com.xzwl.cloud.promotion.application.application;

import java.time.Instant;

/** Application input for approving a promotion application. */
public record ApprovePromotionApplicationCommand(
    String applicationId, String adminId, Instant decisionTime) {}
