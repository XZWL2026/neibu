package com.xzwl.cloud.promotion.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/** A rate in the inclusive range zero to one, normalized to six fractional digits. */
public final class Rate6 {

  private static final int SCALE = 6;
  private static final int MAX_INPUT_LENGTH = 64;
  private static final BigDecimal MIN = BigDecimal.ZERO;
  private static final BigDecimal MAX = BigDecimal.ONE;
  private static final Pattern PLAIN_DECIMAL = Pattern.compile("\\+?\\d+(?:\\.\\d+)?");

  public static final Rate6 ZERO = new Rate6(BigDecimal.ZERO);
  public static final Rate6 ONE = new Rate6(BigDecimal.ONE);

  private final BigDecimal value;

  private Rate6(BigDecimal value) {
    if (value.compareTo(MIN) < 0 || value.compareTo(MAX) > 0) {
      throw new IllegalArgumentException("rate must be between zero and one");
    }
    this.value = value.setScale(SCALE, RoundingMode.DOWN);
  }

  public static Rate6 parse(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("rate must be a plain decimal string");
    }
    if (value.length() > MAX_INPUT_LENGTH) {
      throw new IllegalArgumentException("rate input is too long");
    }
    if (!PLAIN_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException("rate must be a plain decimal string");
    }
    return new Rate6(new BigDecimal(value));
  }

  public static Rate6 of(String value) {
    return parse(value);
  }

  static Rate6 fromBigDecimal(BigDecimal value) {
    if (value == null) {
      throw new IllegalArgumentException("rate is required");
    }
    return new Rate6(value);
  }

  BigDecimal decimalValue() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Rate6 rate6)) {
      return false;
    }
    return value.equals(rate6.value);
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
