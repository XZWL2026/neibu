package com.xzwl.cloud.promotion.domain.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ApplicantSnapshot(
    String userId,
    Optional<String> avatarUrl,
    Optional<String> nickname,
    Optional<String> phone,
    Optional<String> email,
    Instant registeredAt,
    AccountType accountType,
    AccountStatus accountStatus) {

  public ApplicantSnapshot {
    userId = requireNonBlank(userId, "userId");
    avatarUrl = requireOptionalText(avatarUrl, "avatarUrl");
    nickname = requireOptionalText(nickname, "nickname");
    phone = requireOptionalText(phone, "phone");
    email = requireOptionalText(email, "email");
    registeredAt = Objects.requireNonNull(registeredAt, "registeredAt");
    accountType = Objects.requireNonNull(accountType, "accountType");
    accountStatus = Objects.requireNonNull(accountStatus, "accountStatus");
  }

  @Override
  public String toString() {
    return "ApplicantSnapshot["
        + "userId=" + userId
        + ", avatarUrl=REDACTED"
        + ", nickname=REDACTED"
        + ", phone=REDACTED"
        + ", email=REDACTED"
        + ", registeredAt=" + registeredAt
        + ", accountType=" + accountType
        + ", accountStatus=" + accountStatus
        + ']';
  }

  private static Optional<String> requireOptionalText(Optional<String> value, String name) {
    Optional<String> optional = Objects.requireNonNull(value, name);
    if (optional.isPresent() && optional.orElseThrow().isBlank()) {
      throw new IllegalArgumentException(name + " must not contain blank text");
    }
    return optional;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
