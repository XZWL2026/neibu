package com.xzwl.cloud.promotion.domain.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class PromotionApplication {

  private final String id;
  private final ApplicantSnapshot applicantSnapshot;
  private final String reason;
  private final String plannedChannel;
  private final boolean agreementsAccepted;
  private final Instant submittedAt;

  private ApplicationStatus status;
  private String decidedBy;
  private Instant decidedAt;
  private String rejectionReason;

  private PromotionApplication(
      String id,
      ApplicantSnapshot applicant,
      String reason,
      String plannedChannel,
      boolean agreementsAccepted,
      Instant submittedAt) {
    this.id = requireNonBlank(id, "id");
    this.applicantSnapshot = applicant;
    this.reason = requireNonBlank(reason, "reason");
    this.plannedChannel = requireNonBlank(plannedChannel, "plannedChannel");
    this.agreementsAccepted = agreementsAccepted;
    this.submittedAt = Objects.requireNonNull(submittedAt, "submittedAt");
    if (applicant.registeredAt().isAfter(this.submittedAt)) {
      throw new IllegalArgumentException("applicant registeredAt must not be after submittedAt");
    }
    this.status = ApplicationStatus.PENDING;
  }

  public static PromotionApplication submit(
      String id,
      ApplicantSnapshot applicant,
      String reason,
      String plannedChannel,
      boolean agreementsAccepted,
      Instant submittedAt) {
    ApplicantSnapshot snapshot = Objects.requireNonNull(applicant, "applicant");
    if (snapshot.accountStatus() == AccountStatus.DISABLED) {
      throw new IneligiblePromoterException("applicant is not eligible to become a promoter");
    }
    if (!agreementsAccepted) {
      throw new IllegalArgumentException("promotion and commission settlement agreements must be accepted");
    }
    return new PromotionApplication(
        id, snapshot, reason, plannedChannel, agreementsAccepted, submittedAt);
  }

  public synchronized DecisionResult approve(String adminId, Instant decisionTime) {
    String validatedAdminId = requireNonBlank(adminId, "adminId");
    Instant validatedDecisionTime = validateDecisionTime(decisionTime);
    if (status == ApplicationStatus.APPROVED) {
      return new DecisionResult(false, status);
    }
    if (status == ApplicationStatus.REJECTED) {
      throw new IllegalStateException("a rejected application cannot be approved");
    }

    status = ApplicationStatus.APPROVED;
    decidedBy = validatedAdminId;
    decidedAt = validatedDecisionTime;
    return new DecisionResult(true, status);
  }

  public synchronized DecisionResult reject(
      String adminId, String reasonForRejection, Instant decisionTime) {
    String validatedAdminId = requireNonBlank(adminId, "adminId");
    String validatedReason = requireNonBlank(reasonForRejection, "rejectionReason");
    Instant validatedDecisionTime = validateDecisionTime(decisionTime);
    if (status == ApplicationStatus.REJECTED) {
      return new DecisionResult(false, status);
    }
    if (status == ApplicationStatus.APPROVED) {
      throw new IllegalStateException("an approved application cannot be rejected");
    }

    status = ApplicationStatus.REJECTED;
    decidedBy = validatedAdminId;
    decidedAt = validatedDecisionTime;
    rejectionReason = validatedReason;
    return new DecisionResult(true, status);
  }

  public String id() {
    return id;
  }

  public String applicantUserId() {
    return applicantSnapshot.userId();
  }

  public AccountType applicantAccountType() {
    return applicantSnapshot.accountType();
  }

  public ApplicantSnapshot applicantSnapshot() {
    return applicantSnapshot;
  }

  public String reason() {
    return reason;
  }

  public String plannedChannel() {
    return plannedChannel;
  }

  public boolean agreementsAccepted() {
    return agreementsAccepted;
  }

  public Instant submittedAt() {
    return submittedAt;
  }

  public synchronized ApplicationStatus status() {
    return status;
  }

  public synchronized Optional<String> decidedBy() {
    return Optional.ofNullable(decidedBy);
  }

  public synchronized Optional<Instant> decidedAt() {
    return Optional.ofNullable(decidedAt);
  }

  public synchronized Optional<String> rejectionReason() {
    return Optional.ofNullable(rejectionReason);
  }

  private Instant validateDecisionTime(Instant decisionTime) {
    Instant value = Objects.requireNonNull(decisionTime, "decisionTime");
    if (value.isBefore(submittedAt)) {
      throw new IllegalArgumentException("decisionTime must not be before submittedAt");
    }
    return value;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
