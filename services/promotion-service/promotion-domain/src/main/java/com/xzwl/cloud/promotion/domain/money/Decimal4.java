package com.xzwl.cloud.promotion.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/** A decimal amount normalized to four fractional digits by truncating toward zero. */
public final class Decimal4 implements Comparable<Decimal4> {

  private static final int SCALE = 4;
  private static final int MAX_INPUT_LENGTH = 64;
  private static final BigDecimal MAX_ABSOLUTE_VALUE =
      new BigDecimal("999999999999999.9999");
  private static final Pattern PLAIN_DECIMAL = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");

  public static final Decimal4 ZERO = new Decimal4(BigDecimal.ZERO);

  private final BigDecimal value;

  private Decimal4(BigDecimal value) {
    BigDecimal normalized = normalize(value);
    if (normalized.abs().compareTo(MAX_ABSOLUTE_VALUE) > 0) {
      throw new IllegalArgumentException("amount exceeds the supported decimal range");
    }
    this.value = normalized;
  }

  public static Decimal4 parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("amount must be a plain decimal string");
    }
    if (value.length() > MAX_INPUT_LENGTH) {
      throw new IllegalArgumentException("amount input is too long");
    }
    if (!PLAIN_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException("amount must be a plain decimal string");
    }
    return new Decimal4(new BigDecimal(value));
  }

  public static Decimal4 of(String value) {
    return parse(value);
  }

  static Decimal4 fromBigDecimal(BigDecimal value) {
    if (value == null) {
      throw new IllegalArgumentException("amount is required");
    }
    return new Decimal4(value);
  }

  public Decimal4 add(Decimal4 other) {
    requireAmount(other);
    return fromBigDecimal(value.add(other.value));
  }

  public Decimal4 subtract(Decimal4 other) {
    requireAmount(other);
    return fromBigDecimal(value.subtract(other.value));
  }

  public Decimal4 multiply(Rate6 rate) {
    if (rate == null) {
      throw new IllegalArgumentException("rate is required");
    }
    return fromBigDecimal(value.multiply(rate.decimalValue()));
  }

  public Decimal4 multiplyThenDivide(Decimal4 multiplier, Decimal4 divisor) {
    requireAmount(multiplier);
    requireAmount(divisor);
    if (divisor.value.signum() == 0) {
      throw new IllegalArgumentException("divisor must not be zero");
    }
    return fromBigDecimal(
        value.multiply(multiplier.value).divide(divisor.value, SCALE, RoundingMode.DOWN));
  }

  @Override
  public int compareTo(Decimal4 other) {
    requireAmount(other);
    return value.compareTo(other.value);
  }

  private static BigDecimal normalize(BigDecimal value) {
    return value.setScale(SCALE, RoundingMode.DOWN);
  }

  private static void requireAmount(Decimal4 amount) {
    if (amount == null) {
      throw new IllegalArgumentException("amount is required");
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Decimal4 decimal4)) {
      return false;
    }
    return value.equals(decimal4.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(value);
  }

  @Override
  public String toString() {
    return value.toPlainString();
  }
}
