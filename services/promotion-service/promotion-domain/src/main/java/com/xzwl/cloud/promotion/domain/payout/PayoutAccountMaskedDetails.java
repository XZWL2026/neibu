package com.xzwl.cloud.promotion.domain.payout;

import java.util.Objects;

public record PayoutAccountMaskedDetails(
    PayoutAccountType type, String maskedIdentifier, boolean qrCodeConfigured) {

  public PayoutAccountMaskedDetails {
    type = Objects.requireNonNull(type, "type");
    if (maskedIdentifier == null || maskedIdentifier.isBlank()) {
      throw new IllegalArgumentException("maskedIdentifier must not be blank");
    }
  }
}
