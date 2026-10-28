package com.xzwl.cloud.promotion.domain.payout;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

final class PayoutFieldValidation {

  static final int NAME_MAX_LENGTH = 100;
  static final int BANK_NAME_MAX_LENGTH = 100;
  static final int ACCOUNT_MAX_LENGTH = 200;
  static final int QR_REFERENCE_MAX_LENGTH = 500;
  static final Pattern MOBILE_NUMBER = Pattern.compile("1[3-9]\\d{9}");

  private PayoutFieldValidation() {}

  static String requireText(String value, String name, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(name + " is too long");
    }
    return value;
  }

  static Optional<String> requireOptionalText(
      Optional<String> value, String name, int maxLength) {
    Optional<String> optional = Objects.requireNonNull(value, name);
    optional.ifPresent(
        text -> {
          if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not contain blank text");
          }
          if (text.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
          }
        });
    return optional;
  }

  static String requireMobileNumber(String value, String name) {
    if (value == null || !MOBILE_NUMBER.matcher(value).matches()) {
      throw new IllegalArgumentException(name + " must be a valid mobile number");
    }
    return value;
  }

  static String maskMobile(String mobile) {
    return mobile.substring(0, 3) + "****" + mobile.substring(7);
  }

  static String maskAlipayAccount(String account) {
    if (MOBILE_NUMBER.matcher(account).matches()) {
      return maskMobile(account);
    }
    int at = account.indexOf('@');
    if (at > 0 && at == account.lastIndexOf('@') && at < account.length() - 1) {
      return account.charAt(0) + "***" + account.substring(at);
    }
    if (account.length() == 1) {
      return "*";
    }
    if (account.length() == 2) {
      return account.charAt(0) + "*";
    }
    return account.charAt(0) + "***" + account.substring(account.length() - 1);
  }
}
