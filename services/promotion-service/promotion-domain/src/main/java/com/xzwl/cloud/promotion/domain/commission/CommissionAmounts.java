package com.xzwl.cloud.promotion.domain.commission;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.math.BigDecimal;
import java.util.Objects;

final class CommissionAmounts {

  private CommissionAmounts() {}

  static Decimal4 requirePositive(Decimal4 amount, String name) {
    Objects.requireNonNull(amount, name);
    if (compare(amount, Decimal4.ZERO) <= 0) {
      throw new IllegalArgumentException(name + " must be positive after Decimal4 normalization");
    }
    return amount;
  }

  static Decimal4 requireNonNegative(Decimal4 amount, String name) {
    Objects.requireNonNull(amount, name);
    if (compare(amount, Decimal4.ZERO) < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return amount;
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
