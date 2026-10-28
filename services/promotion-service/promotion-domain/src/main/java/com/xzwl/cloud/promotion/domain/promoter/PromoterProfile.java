package com.xzwl.cloud.promotion.domain.promoter;

import com.xzwl.cloud.promotion.domain.invitation.InvitationCode;
import com.xzwl.cloud.promotion.domain.invitation.InvitationProfile;
import java.time.Instant;
import java.util.Objects;

public final class PromoterProfile {

  private final String id;
  private final String userId;
  private final InvitationCode invitationCode;
  private final Instant createdAt;

  private PromoterProfile(
      String id, String userId, InvitationCode invitationCode, Instant createdAt) {
    this.id = id;
    this.userId = userId;
    this.invitationCode = invitationCode;
    this.createdAt = createdAt;
  }

  public static PromoterProfile create(
      String id, String userId, InvitationProfile invitationProfile, Instant createdAt) {
    String validatedId = requireNonBlank(id, "id");
    String validatedUserId = requireNonBlank(userId, "userId");
    InvitationProfile validatedInvitationProfile =
        Objects.requireNonNull(invitationProfile, "invitationProfile");
    Instant validatedCreatedAt = Objects.requireNonNull(createdAt, "createdAt");
    if (!validatedUserId.equals(validatedInvitationProfile.userId())) {
      throw new IllegalArgumentException("promoter userId must match invitation profile userId");
    }
    return new PromoterProfile(
        validatedId,
        validatedUserId,
        validatedInvitationProfile.code(),
        validatedCreatedAt);
  }

  public String id() {
    return id;
  }

  public String userId() {
    return userId;
  }

  public InvitationCode invitationCode() {
    return invitationCode;
  }

  public Instant createdAt() {
    return createdAt;
  }

  @Override
  public String toString() {
    return "PromoterProfile[redacted]";
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
