package com.xzwl.cloud.billing.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class Decimal4 implements Comparable<Decimal4> {

  private static final int SCALE = 4;
  private static final Decimal4 ZERO = new Decimal4(BigDecimal.ZERO);

  private final BigDecimal value;

  private Decimal4(BigDecimal value) {
    this.value = Objects.requireNonNull(value, "value").setScale(SCALE, RoundingMode.DOWN);
  }

  public static Decimal4 of(String value) {
    return new Decimal4(new BigDecimal(Objects.requireNonNull(value, "value")));
  }

  public static Decimal4 of(BigDecimal value) {
    return new Decimal4(value);
  }

  public static Decimal4 zero() {
    return ZERO;
  }

  public BigDecimal asBigDecimal() {
    return value;
  }

  public Decimal4 add(Decimal4 other) {
    return new Decimal4(value.add(require(other).value));
  }

  public Decimal4 subtract(Decimal4 other) {
    return new Decimal4(value.subtract(require(other).value));
  }

  public Decimal4 multiply(Decimal4 other) {
    return new Decimal4(value.multiply(require(other).value));
  }

  public boolean isPositive() {
    return value.signum() > 0;
  }

  public boolean isNegative() {
    return value.signum() < 0;
  }

  @Override
  public int compareTo(Decimal4 other) {
    return value.compareTo(require(other).value);
  }

  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof Decimal4 decimal4 && value.equals(decimal4.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value.toPlainString();
  }

  private static Decimal4 require(Decimal4 value) {
    return Objects.requireNonNull(value, "value");
  }
}
