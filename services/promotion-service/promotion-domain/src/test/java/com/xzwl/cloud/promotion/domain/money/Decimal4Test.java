package com.xzwl.cloud.promotion.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Decimal4Test {

  private static final String MAX_VALUE = "999999999999999.9999";
  private static final String MIN_VALUE = "-999999999999999.9999";

  @Test
  void normalizesToFourPlacesByTruncatingTowardZero() {
    assertThat(Decimal4.parse("1.23459").toString()).isEqualTo("1.2345");
    assertThat(Decimal4.parse("-1.23459").toString()).isEqualTo("-1.2345");
    assertThat(Decimal4.parse("12").toString()).isEqualTo("12.0000");
    assertThat(Decimal4.ZERO.toString()).isEqualTo("0.0000");
  }

  @ParameterizedTest
  @MethodSource("invalidDecimals")
  void rejectsAnythingOtherThanAPlainFiniteDecimalString(String value) {
    assertThatThrownBy(() -> Decimal4.parse(value)).isInstanceOf(IllegalArgumentException.class);
  }

  private static Stream<Arguments> invalidDecimals() {
    return Stream.of(
        Arguments.of((String) null),
        Arguments.of(""),
        Arguments.of("  "),
        Arguments.of(" 1.0"),
        Arguments.of("1.0 "),
        Arguments.of("1e2"),
        Arguments.of("NaN"),
        Arguments.of("Infinity"),
        Arguments.of(".25"),
        Arguments.of("1."),
        Arguments.of("--1"));
  }

  @Test
  void equalityAndHashCodeUseTheNormalizedNumericValue() {
    assertThat(Decimal4.parse("1.2"))
        .isEqualTo(Decimal4.parse("1.20009"))
        .hasSameHashCodeAs(Decimal4.parse("1.2000"));
  }

  @Test
  void enforcesDecimal19Scale4RangeAfterNormalization() {
    assertThat(Decimal4.parse(MAX_VALUE).toString()).isEqualTo(MAX_VALUE);
    assertThat(Decimal4.parse(MIN_VALUE).toString()).isEqualTo(MIN_VALUE);
    assertThat(Decimal4.parse(MAX_VALUE + "9").toString()).isEqualTo(MAX_VALUE);
    assertThat(Decimal4.parse(MIN_VALUE + "9").toString()).isEqualTo(MIN_VALUE);

    assertThatThrownBy(() -> Decimal4.parse("1000000000000000.0000"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Decimal4.parse("-1000000000000000.0000"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOverlongInputBeforeDecimalParsingWithoutEchoingIt() {
    String overlongInput = "0." + "0".repeat(63);

    assertThat(overlongInput).hasSize(65);
    assertThatThrownBy(() -> Decimal4.parse(overlongInput))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(overlongInput);
  }

  @Test
  void rejectsOutOfRangeAdditionAndSubtractionResults() {
    Decimal4 maximum = Decimal4.parse(MAX_VALUE);
    Decimal4 minimum = Decimal4.parse(MIN_VALUE);

    assertThatThrownBy(() -> maximum.add(Decimal4.parse("0.0001")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> maximum.subtract(Decimal4.parse("-0.0001")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> minimum.subtract(Decimal4.parse("0.0001")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void maximumMagnitudeMultiplyByOneRemainsWithinRange() {
    assertThat(Decimal4.parse(MAX_VALUE).multiply(Rate6.ONE))
        .isEqualTo(Decimal4.parse(MAX_VALUE));
    assertThat(Decimal4.parse(MIN_VALUE).multiply(Rate6.ONE))
        .isEqualTo(Decimal4.parse(MIN_VALUE));
  }

  @Test
  void arithmeticNormalizesEveryResultWithoutBinaryFloatingPoint() {
    assertThat(Decimal4.parse("1.00009").add(Decimal4.parse("2.00009")))
        .isEqualTo(Decimal4.parse("3.0000"));
    assertThat(Decimal4.parse("1.0000").subtract(Decimal4.parse("2.23459")))
        .isEqualTo(Decimal4.parse("-1.2345"));
    assertThat(Decimal4.parse("100.0000").multiply(Rate6.parse("0.030000")))
        .isEqualTo(Decimal4.parse("3.0000"));
    assertThat(Decimal4.parse("33.3333").multiply(Rate6.parse("0.030000")))
        .isEqualTo(Decimal4.parse("0.9999"));
  }

  @Test
  void publicApiDoesNotAcceptOrReturnBinaryFloatingPoint() {
    assertThat(Decimal4.class.getMethods())
        .filteredOn(method -> method.getDeclaringClass().equals(Decimal4.class))
        .allSatisfy(Decimal4Test::assertNoBinaryFloatingPoint);
  }

  private static void assertNoBinaryFloatingPoint(Method method) {
    assertThat(method.getReturnType()).isNotIn(double.class, float.class, Double.class, Float.class);
    assertThat(method.getParameterTypes())
        .doesNotContain(double.class, float.class, Double.class, Float.class);
  }
}
