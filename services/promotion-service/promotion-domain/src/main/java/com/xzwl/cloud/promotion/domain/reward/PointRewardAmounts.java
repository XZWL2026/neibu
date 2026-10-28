package com.xzwl.cloud.promotion.domain.reward;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.math.BigDecimal;
import java.util.Objects;

final class PointRewardAmounts {

  private PointRewardAmounts() {}

  static Decimal4 requirePositive(Decimal4 value, String name) {
    Objects.requireNonNull(value, name);
    if (compare(value, Decimal4.ZERO) <= 0) {
      throw new IllegalArgumentException(name + " must be positive after Decimal4 normalization");
    }
    return value;
  }

  static Decimal4 requireNonNegative(Decimal4 value, String name) {
    Objects.requireNonNull(value, name);
    if (compare(value, Decimal4.ZERO) < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }

  static int compare(Decimal4 left, Decimal4 right) {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
    return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
  }

  static Decimal4 min(Decimal4 left, Decimal4 right) {
    return compare(left, right) <= 0 ? left : right;
  }

  static String requireId(String value, String name) {
    return PromotionIdentifiers.requireId(value, name);
  }
}
