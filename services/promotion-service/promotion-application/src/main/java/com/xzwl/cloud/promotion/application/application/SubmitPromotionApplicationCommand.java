package com.xzwl.cloud.promotion.application.application;

import com.xzwl.cloud.promotion.domain.application.ApplicantSnapshot;
import java.time.Instant;

/** Application input for submitting a promotion application. */
public record SubmitPromotionApplicationCommand(
    String applicationId,
    ApplicantSnapshot applicant,
    String reason,
    String plannedChannel,
    boolean agreementsAccepted,
    Instant submittedAt) {}
