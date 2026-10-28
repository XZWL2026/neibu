package com.xzwl.cloud.promotion.domain.payout;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import java.time.Instant;
import java.util.Objects;

public final class PayoutAccount {

  private final String id;
  private final String promoterId;
  private final PayoutAccountType type;
  private final Instant createdAt;
  private PayoutAccountDetails details;
  private PayoutAccountStatus status;
  private Instant updatedAt;

  private PayoutAccount(
      String id,
      String promoterId,
      PayoutAccountType type,
      PayoutAccountDetails details,
      Instant createdAt) {
    this.id = id;
    this.promoterId = promoterId;
    this.type = type;
    this.details = details;
    this.status = PayoutAccountStatus.ACTIVE;
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
  }

  public static PayoutAccount create(
      String id,
      String promoterId,
      PayoutAccountType type,
      PayoutAccountDetails details,
      Instant createdAt) {
    String validatedId = PromotionIdentifiers.requireId(id, "id");
    String validatedPromoterId = PromotionIdentifiers.requireId(promoterId, "promoterId");
    PayoutAccountType validatedType = Objects.requireNonNull(type, "type");
    PayoutAccountDetails validatedDetails = requireMatchingDetails(validatedType, details);
    Instant validatedCreatedAt = Objects.requireNonNull(createdAt, "createdAt");
    return new PayoutAccount(
        validatedId,
        validatedPromoterId,
        validatedType,
        validatedDetails,
        validatedCreatedAt);
  }

  public void update(PayoutAccountDetails replacement, Instant changedAt) {
    if (status == PayoutAccountStatus.DISABLED) {
      throw new IllegalStateException("disabled payout account cannot be modified");
    }
    PayoutAccountDetails validatedReplacement = requireMatchingDetails(type, replacement);
    Instant validatedChangedAt = requireNotBefore(changedAt, "changedAt", updatedAt);
    details = validatedReplacement;
    updatedAt = validatedChangedAt;
  }

  public void disable(Instant changedAt) {
    Instant validatedChangedAt = requireNotBefore(changedAt, "changedAt", updatedAt);
    if (status == PayoutAccountStatus.ACTIVE) {
      status = PayoutAccountStatus.DISABLED;
      updatedAt = validatedChangedAt;
    }
  }

  public PayoutAccountSnapshot snapshot(Instant capturedAt) {
    Instant validatedCapturedAt = requireNotBefore(capturedAt, "capturedAt", updatedAt);
    return new PayoutAccountSnapshot(id, promoterId, type, details, validatedCapturedAt);
  }

  public PayoutAccountMaskedDetails maskedDetails() {
    return details.masked();
  }

  public String id() {
    return id;
  }

  public String promoterId() {
    return promoterId;
  }

  public PayoutAccountType type() {
    return type;
  }

  public PayoutAccountDetails details() {
    return details;
  }

  public PayoutAccountStatus status() {
    return status;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  @Override
  public String toString() {
    return "PayoutAccount[id=" + id + ", promoterId=" + promoterId + ", type=" + type
        + ", status=" + status + ", details=REDACTED]";
  }

  private static PayoutAccountDetails requireMatchingDetails(
      PayoutAccountType type, PayoutAccountDetails details) {
    PayoutAccountDetails validatedDetails = Objects.requireNonNull(details, "details");
    if (validatedDetails.type() != type) {
      throw new IllegalArgumentException("details must match payout account type");
    }
    return validatedDetails;
  }

  private static Instant requireNotBefore(Instant value, String name, Instant lowerBound) {
    Instant validated = Objects.requireNonNull(value, name);
    if (validated.isBefore(lowerBound)) {
      throw new IllegalArgumentException(name + " must not be before updatedAt");
    }
    return validated;
  }
}
