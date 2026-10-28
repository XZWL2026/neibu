package com.xzwl.cloud.promotion.domain.payout;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import java.time.Instant;
import java.util.Objects;

public record PayoutAccountSnapshot(
    String accountId,
    String promoterId,
    PayoutAccountType type,
    PayoutAccountDetails details,
    Instant capturedAt) {

  public PayoutAccountSnapshot {
    accountId = PromotionIdentifiers.requireId(accountId, "accountId");
    promoterId = PromotionIdentifiers.requireId(promoterId, "promoterId");
    type = Objects.requireNonNull(type, "type");
    details = Objects.requireNonNull(details, "details");
    capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    if (details.type() != type) {
      throw new IllegalArgumentException("details must match payout account type");
    }
  }

  @Override
  public String toString() {
    return "PayoutAccountSnapshot[accountId=" + accountId + ", promoterId=" + promoterId
        + ", type=" + type + ", details=REDACTED, capturedAt=" + capturedAt + ']';
  }
}
