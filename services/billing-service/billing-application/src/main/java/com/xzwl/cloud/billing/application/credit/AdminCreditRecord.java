package com.xzwl.cloud.billing.application.credit;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.time.Instant;
import java.util.Objects;

/** Back-office funding record; it intentionally contains no payment-provider fields. */
public record AdminCreditRecord(
    String requestId,
    String walletId,
    String keyId,
    Decimal4 points4,
    String adminId,
    Instant at,
    String remark) {

  public AdminCreditRecord {
    requireNonBlank(requestId, "requestId");
    requireNonBlank(walletId, "walletId");
    requireNonBlank(keyId, "keyId");
    points4 = Objects.requireNonNull(points4, "points4");
    if (!points4.isPositive()) throw new IllegalArgumentException("points4 must be positive");
    requireNonBlank(adminId, "adminId");
    Objects.requireNonNull(at, "at");
    requireNonBlank(remark, "remark");
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
  }

  /** Server receipt time is metadata; a retry returns the original timestamp and result. */
  public boolean sameFacts(AdminCreditRecord other) {
    return requestId.equals(other.requestId) && walletId.equals(other.walletId) && keyId.equals(other.keyId)
        && points4.equals(other.points4) && adminId.equals(other.adminId) && remark.equals(other.remark);
  }
}
