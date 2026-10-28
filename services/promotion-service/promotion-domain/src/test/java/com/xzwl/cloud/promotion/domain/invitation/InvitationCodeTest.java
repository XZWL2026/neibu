package com.xzwl.cloud.promotion.domain.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class InvitationCodeTest {

  @ParameterizedTest
  @ValueSource(strings = {"ABC123", "000000", "ZZZZZZ"})
  void acceptsExactlySixUppercaseLettersOrDigits(String value) {
    assertThat(new InvitationCode(value).value()).isEqualTo(value);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ABC123", "XYZ789"})
  void doesNotExposeTheCodeThroughToString(String value) {
    assertThat(new InvitationCode(value)).hasToString("InvitationCode[redacted]");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "      ", "ABC12", "ABC1234", "abc123", "ABC 12", "ABC-12"})
  void rejectsValuesOutsideTheCanonicalFormat(String value) {
    assertThatThrownBy(() -> new InvitationCode(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("invitation code must match [A-Z0-9]{6}");
  }
}
