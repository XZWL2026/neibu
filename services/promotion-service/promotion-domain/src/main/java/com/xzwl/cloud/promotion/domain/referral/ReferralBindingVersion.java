package com.xzwl.cloud.promotion.domain.referral;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ReferralBindingVersion(
    long versionNo,
    String referredUserId,
    String directInviterUserId,
    BenefitSnapshot benefitSnapshot,
    Instant effectiveFrom,
    ReferralBindingSource source,
    ReferralWritePrecondition writePrecondition,
    String adminCommandReference,
    String adminSemanticFingerprint,
    String adminReference,
    String changeReason) {

  public ReferralBindingVersion {
    if (versionNo < 1) {
      throw new IllegalArgumentException("versionNo must be positive");
    }
    referredUserId = requireNonBlank(referredUserId, "referredUserId");
    directInviterUserId = requireNonBlank(directInviterUserId, "directInviterUserId");
    benefitSnapshot = Objects.requireNonNull(benefitSnapshot, "benefitSnapshot");
    validateReferralBenefits(referredUserId, directInviterUserId, benefitSnapshot);
    effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    source = Objects.requireNonNull(source, "source");
    writePrecondition = Objects.requireNonNull(writePrecondition, "writePrecondition");
    if (versionNo != writePrecondition.expectedAggregateVersionNo() + 1) {
      throw new IllegalArgumentException(
          "versionNo must equal expectedAggregateVersionNo + 1");
    }

    if (source == ReferralBindingSource.REGISTRATION) {
      if (versionNo != 1) {
        throw new IllegalArgumentException("REGISTRATION requires version 1");
      }
      if (adminCommandReference != null
          || adminSemanticFingerprint != null
          || adminReference != null
          || changeReason != null) {
        throw new IllegalArgumentException("registration binding cannot include admin fields");
      }
    } else {
      if (source == ReferralBindingSource.ADMIN_BIND && versionNo != 1) {
        throw new IllegalArgumentException("ADMIN_BIND requires version 1");
      }
      if (source == ReferralBindingSource.ADMIN_REBIND && versionNo < 2) {
        throw new IllegalArgumentException("ADMIN_REBIND requires version 2 or later");
      }
      adminCommandReference = requireNonBlank(adminCommandReference, "adminCommandReference");
      adminSemanticFingerprint = requireFingerprint(adminSemanticFingerprint);
      adminReference = requireNonBlank(adminReference, "adminReference");
      changeReason = requireNonBlank(changeReason, "changeReason");
    }
  }

  public Optional<String> adminCommandId() {
    return Optional.ofNullable(adminCommandReference);
  }

  private static String requireFingerprint(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "adminSemanticFingerprint must be a lowercase SHA-256 value");
    }
    return value;
  }

  private static void validateReferralBenefits(
      String referredUserId,
      String directInviterUserId,
      BenefitSnapshot benefitSnapshot) {
    if (referredUserId.equals(directInviterUserId)) {
      throw new IllegalArgumentException("self invitation is not allowed");
    }
    if (referredUserId.equals(benefitSnapshot.pointBeneficiaryUserId())
        || referredUserId.equals(benefitSnapshot.cashPromoterUserId())) {
      throw new IllegalArgumentException("benefits must not point to referredUserId");
    }

    switch (benefitSnapshot.cashTier()) {
      case LEVEL_1 -> {
        if (benefitSnapshot.pointBeneficiaryUserId() != null
            || !directInviterUserId.equals(benefitSnapshot.cashPromoterUserId())) {
          throw new IllegalArgumentException(
              "LEVEL_1 requires only the direct inviter as cash promoter");
        }
      }
      case LEVEL_2 -> {
        if (!directInviterUserId.equals(benefitSnapshot.pointBeneficiaryUserId())) {
          throw new IllegalArgumentException(
              "LEVEL_2 point beneficiary must be the direct inviter");
        }
        if (directInviterUserId.equals(benefitSnapshot.cashPromoterUserId())) {
          throw new IllegalArgumentException(
              "LEVEL_2 cash promoter must be above the direct inviter");
        }
      }
      case NONE -> {
        if (!directInviterUserId.equals(benefitSnapshot.pointBeneficiaryUserId())
            || benefitSnapshot.cashPromoterUserId() != null) {
          throw new IllegalArgumentException(
              "NONE point beneficiary must be the direct inviter with no cash promoter");
        }
      }
    }
  }

  public Optional<String> adminId() {
    return Optional.ofNullable(adminReference);
  }

  public Optional<String> reason() {
    return Optional.ofNullable(changeReason);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  @Override
  public String toString() {
    return "ReferralBindingVersion[versionNo=%d, effectiveFrom=%s, source=%s, cashTier=%s]"
        .formatted(versionNo, effectiveFrom, source, benefitSnapshot.cashTier());
  }
}
