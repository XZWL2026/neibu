package com.xzwl.cloud.promotion.domain.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.application.AccountType;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InvitationEligibilityTest {

  private static final Set<AccountType> ALLOWED_ACCOUNT_TYPES =
      Set.of(AccountType.PERSONAL, AccountType.ENTERPRISE, AccountType.API);

  @ParameterizedTest
  @MethodSource("allAccountCombinations")
  void onlyAllowsExplicitAccountTypesWithEnabledStatus(
      AccountType accountType, AccountStatus accountStatus, boolean expectedEligible) {
    InvitationEligibility eligibility = new InvitationEligibility(accountType, accountStatus);

    assertThat(eligibility.isEligible()).isEqualTo(expectedEligible);
    if (expectedEligible) {
      assertThatCode(eligibility::requireEligible).doesNotThrowAnyException();
    } else {
      assertThatThrownBy(eligibility::requireEligible)
          .isInstanceOf(IneligibleInvitationAccountException.class)
          .hasMessage("account is not eligible for an invitation profile");
    }
  }

  @Test
  void rejectsMissingAccountFacts() {
    assertThatThrownBy(() -> new InvitationEligibility(null, AccountStatus.ENABLED))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("accountType");
    assertThatThrownBy(() -> new InvitationEligibility(AccountType.PERSONAL, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("accountStatus");
  }

  private static Stream<Arguments> allAccountCombinations() {
    return Arrays.stream(AccountType.values())
        .flatMap(
            accountType ->
                Arrays.stream(AccountStatus.values())
                    .map(
                        accountStatus ->
                            Arguments.of(
                                accountType,
                                accountStatus,
                                accountStatus == AccountStatus.ENABLED
                                    && ALLOWED_ACCOUNT_TYPES.contains(accountType))));
  }
}
