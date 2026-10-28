package com.xzwl.cloud.promotion.domain.invitation;

import java.util.regex.Pattern;

public record InvitationCode(String value) {

  private static final Pattern FORMAT = Pattern.compile("[A-Z0-9]{6}");

  public InvitationCode {
    if (value == null || !FORMAT.matcher(value).matches()) {
      throw new IllegalArgumentException("invitation code must match [A-Z0-9]{6}");
    }
  }

  @Override
  public String toString() {
    return "InvitationCode[redacted]";
  }
}
