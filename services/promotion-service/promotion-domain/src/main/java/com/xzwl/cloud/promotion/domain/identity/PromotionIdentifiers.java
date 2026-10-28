package com.xzwl.cloud.promotion.domain.identity;

/** Shared trust boundary for identifiers that flow through promotion domain facts. */
public final class PromotionIdentifiers {

  public static final int MAX_LENGTH = 200;

  private PromotionIdentifiers() {}

  public static String requireId(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(name + " is too long");
    }
    return value;
  }

  public static String optionalId(String value, String name) {
    return value == null ? null : requireId(value, name);
  }
}
