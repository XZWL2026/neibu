package com.xzwl.cloud.promotion.application.application;

import java.time.Instant;

/** Application input for rejecting a promotion application. */
public record RejectPromotionApplicationCommand(
    String applicationId, String adminId, String rejectionReason, Instant decisionTime) {}
