package com.xzwl.cloud.promotion.domain.invitation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class InvitationProfile {

  private final String userId;
  private final InvitationCode code;
  private final Instant createdAt;

  private InvitationProfile(String userId, InvitationCode code, Instant createdAt) {
    this.userId = userId;
    this.code = code;
    this.createdAt = createdAt;
  }

  public static InvitationProfile provision(
      Optional<InvitationProfile> existingProfile,
      String userId,
      InvitationCode code,
      InvitationEligibility eligibility,
      Instant createdAt) {
    Optional<InvitationProfile> existing =
        Objects.requireNonNull(existingProfile, "existingProfile");
    String validatedUserId = requireNonBlank(userId, "userId");
    InvitationCode validatedCode = Objects.requireNonNull(code, "code");
    InvitationEligibility validatedEligibility =
        Objects.requireNonNull(eligibility, "eligibility");
    Instant validatedCreatedAt = Objects.requireNonNull(createdAt, "createdAt");
    validatedEligibility.requireEligible();
    if (existing.isPresent()) {
      return existing.get().resolveRepeatedProvision(validatedUserId, validatedCode);
    }
    return new InvitationProfile(validatedUserId, validatedCode, validatedCreatedAt);
  }

  private InvitationProfile resolveRepeatedProvision(
      String requestedUserId, InvitationCode requestedCode) {
    if (!userId.equals(requestedUserId) || !code.equals(requestedCode)) {
      throw new InvitationProfileConflictException();
    }
    return this;
  }

  public String userId() {
    return userId;
  }

  public InvitationCode code() {
    return code;
  }

  public Instant createdAt() {
    return createdAt;
  }

  @Override
  public String toString() {
    return "InvitationProfile[redacted]";
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
