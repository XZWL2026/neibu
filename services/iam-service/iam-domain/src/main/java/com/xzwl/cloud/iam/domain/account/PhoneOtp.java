package com.xzwl.cloud.iam.domain.account;

import java.time.LocalDate;

public record PhoneOtp(String phone, String codeHash, long expiresAt, Long lastSentAt,
                       LocalDate sentDay, int sentCount, int failures, boolean consumed) {
  public PhoneOtp consumedCopy() { return new PhoneOtp(phone, null, expiresAt, lastSentAt, sentDay, sentCount, failures, true); }
  public PhoneOtp wrongAttempt() { return new PhoneOtp(phone, codeHash, expiresAt, lastSentAt, sentDay, sentCount, Math.min(5, failures + 1), consumed); }
}
