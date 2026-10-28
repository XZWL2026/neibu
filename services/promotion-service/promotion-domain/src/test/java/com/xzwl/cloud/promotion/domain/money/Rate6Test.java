package com.xzwl.cloud.promotion.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Rate6Test {

  @Test
  void acceptsBoundariesAndTruncatesToSixPlaces() {
    assertThat(Rate6.parse("0")).isEqualTo(Rate6.ZERO);
    assertThat(Rate6.parse("1")).isEqualTo(Rate6.ONE);
    assertThat(Rate6.parse("0.100000").toString()).isEqualTo("0.100000");
    assertThat(Rate6.parse("0.1234569").toString()).isEqualTo("0.123456");
  }

  @ParameterizedTest
  @MethodSource("invalidRates")
  void rejectsInvalidOrOutOfRangeRates(String value) {
    assertThatThrownBy(() -> Rate6.parse(value)).isInstanceOf(IllegalArgumentException.class);
  }

  private static Stream<Arguments> invalidRates() {
    return Stream.of(
        Arguments.of((String) null),
        Arguments.of(""),
        Arguments.of(" "),
        Arguments.of(" 0.1"),
        Arguments.of("0.1 "),
        Arguments.of("1e-2"),
        Arguments.of("NaN"),
        Arguments.of("Infinity"),
        Arguments.of("-0"),
        Arguments.of("-0.000000"),
        Arguments.of("-0.000001"),
        Arguments.of("1.0000001"),
        Arguments.of("2"),
        Arguments.of(".1"),
        Arguments.of("1."));
  }

  @Test
  void equalityAndHashCodeUseTheNormalizedRate() {
    assertThat(Rate6.parse("0.1"))
        .isEqualTo(Rate6.parse("0.1000009"))
        .hasSameHashCodeAs(Rate6.parse("0.100000"));
  }

  @Test
  void rejectsOverlongInputBeforeRateParsingWithoutEchoingIt() {
    String overlongInput = "0." + "1".repeat(63);

    assertThat(overlongInput).hasSize(65);
    assertThatThrownBy(() -> Rate6.parse(overlongInput))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(overlongInput);
  }
}
