package com.xzwl.cloud.promotion.domain.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.application.AccountType;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class InvitationProfileTest {

  private static final Instant CREATED_AT = Instant.parse("2026-08-30T03:00:00Z");

  @ParameterizedTest
  @EnumSource(value = AccountType.class, names = {"PERSONAL", "ENTERPRISE", "API"})
  void provisionsAPermanentProfileForEveryEnabledSupportedAccountType(AccountType accountType) {
    InvitationCode code = new InvitationCode("ABC123");

    InvitationProfile profile =
        InvitationProfile.provision(
            Optional.empty(),
            "user-1",
            code,
            new InvitationEligibility(accountType, AccountStatus.ENABLED),
            CREATED_AT);

    assertThat(profile.userId()).isEqualTo("user-1");
    assertThat(profile.code()).isSameAs(code);
    assertThat(profile.createdAt()).isEqualTo(CREATED_AT);
  }

  @ParameterizedTest
  @MethodSource("ineligibleAccounts")
  void refusesToProvisionUnsetOrDisabledAccounts(
      AccountType accountType, AccountStatus accountStatus) {
    InvitationEligibility eligibility = new InvitationEligibility(accountType, accountStatus);

    assertThatThrownBy(
            () ->
                InvitationProfile.provision(
                    Optional.empty(),
                    "user-1",
                    new InvitationCode("ABC123"),
                    eligibility,
                    CREATED_AT))
        .isInstanceOf(IneligibleInvitationAccountException.class)
        .hasMessage("account is not eligible for an invitation profile");
  }

  @Test
  void repeatedProvisionWithTheSameUserAndCodeReturnsTheExistingPermanentFact() {
    InvitationProfile existing = eligibleProfile("user-1", "ABC123", CREATED_AT);

    InvitationProfile repeated =
        InvitationProfile.provision(
            Optional.of(existing),
            "user-1",
            new InvitationCode("ABC123"),
            eligibleEligibility(),
            CREATED_AT.plusSeconds(60));

    assertThat(repeated).isSameAs(existing);
    assertThat(repeated.createdAt()).isEqualTo(CREATED_AT);
  }

  @Test
  void repeatedProvisionCannotReplaceThePermanentCode() {
    InvitationProfile existing = eligibleProfile("user-1", "ABC123", CREATED_AT);

    assertThatThrownBy(
            () ->
                InvitationProfile.provision(
                    Optional.of(existing),
                    "user-1",
                    new InvitationCode("XYZ789"),
                    eligibleEligibility(),
                    CREATED_AT.plusSeconds(60)))
        .isInstanceOf(InvitationProfileConflictException.class)
        .hasMessage("invitation profile conflicts with the existing permanent identity");
    assertThat(existing.code()).isEqualTo(new InvitationCode("ABC123"));
  }

  @Test
  void repeatedProvisionCannotReuseAnotherUsersProfile() {
    InvitationProfile existing = eligibleProfile("user-1", "ABC123", CREATED_AT);

    assertThatThrownBy(
            () ->
                InvitationProfile.provision(
                    Optional.of(existing),
                    "user-2",
                    new InvitationCode("ABC123"),
                    eligibleEligibility(),
                    CREATED_AT.plusSeconds(60)))
        .isInstanceOf(InvitationProfileConflictException.class)
        .hasMessage("invitation profile conflicts with the existing permanent identity");
  }

  @Test
  void acceptsTheInstantEpochBoundary() {
    InvitationProfile profile = eligibleProfile("user-1", "ABC123", Instant.EPOCH);

    assertThat(profile.createdAt()).isEqualTo(Instant.EPOCH);
  }

  @Test
  void doesNotExposeIdentityOrCodeThroughToString() {
    InvitationProfile profile = eligibleProfile("private-user-id", "ABC123", CREATED_AT);

    assertThat(profile).hasToString("InvitationProfile[redacted]");
  }

  @Test
  void rejectsMissingIdentityCodeEligibilityAndTime() {
    InvitationCode code = new InvitationCode("ABC123");
    InvitationEligibility eligibility =
        new InvitationEligibility(AccountType.PERSONAL, AccountStatus.ENABLED);

    assertThatThrownBy(
            () -> InvitationProfile.provision(Optional.empty(), null, code, eligibility, CREATED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("userId must not be blank");
    assertThatThrownBy(
            () -> InvitationProfile.provision(Optional.empty(), " ", code, eligibility, CREATED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("userId must not be blank");
    assertThatThrownBy(
            () ->
                InvitationProfile.provision(
                    Optional.empty(), "user-1", null, eligibility, CREATED_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("code");
    assertThatThrownBy(
            () ->
                InvitationProfile.provision(
                    Optional.empty(), "user-1", code, null, CREATED_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("eligibility");
    assertThatThrownBy(
            () ->
                InvitationProfile.provision(
                    Optional.empty(), "user-1", code, eligibility, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("createdAt");
    assertThatThrownBy(
            () -> InvitationProfile.provision(null, "user-1", code, eligibility, CREATED_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("existingProfile");
    assertThatThrownBy(
            () ->
                InvitationProfile.provision(
                    Optional.of(eligibleProfile("user-1", "ABC123", CREATED_AT)),
                    null,
                    code,
                    eligibility,
                    CREATED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("userId must not be blank");
    assertThatThrownBy(
            () ->
                InvitationProfile.provision(
                    Optional.of(eligibleProfile("user-1", "ABC123", CREATED_AT)),
                    "user-1",
                    null,
                    eligibility,
                    CREATED_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("code");
  }

  private static InvitationProfile eligibleProfile(
      String userId, String code, Instant createdAt) {
    return InvitationProfile.provision(
        Optional.empty(),
        userId,
        new InvitationCode(code),
        eligibleEligibility(),
        createdAt);
  }

  private static InvitationEligibility eligibleEligibility() {
    return new InvitationEligibility(AccountType.PERSONAL, AccountStatus.ENABLED);
  }

  private static Stream<Arguments> ineligibleAccounts() {
    return Stream.of(
        Arguments.of(AccountType.UNSET, AccountStatus.ENABLED),
        Arguments.of(AccountType.PERSONAL, AccountStatus.DISABLED),
        Arguments.of(AccountType.ENTERPRISE, AccountStatus.DISABLED),
        Arguments.of(AccountType.API, AccountStatus.DISABLED));
  }
}
