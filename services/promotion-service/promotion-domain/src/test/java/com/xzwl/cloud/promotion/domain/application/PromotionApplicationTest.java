package com.xzwl.cloud.promotion.domain.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PromotionApplicationTest {

  private static final Instant SUBMITTED_AT = Instant.parse("2026-08-30T01:00:00Z");
  private static final Instant REGISTERED_AT = Instant.parse("2026-08-01T01:00:00Z");
  private static final Instant DECIDED_AT = Instant.parse("2026-08-30T02:00:00Z");

  @ParameterizedTest
  @MethodSource("eligibleApplicants")
  void enabledSupportedAccountTypesCanSubmit(AccountType accountType) {
    PromotionApplication application = submit("application-1", accountType, AccountStatus.ENABLED);

    assertThat(application.status()).isEqualTo(ApplicationStatus.PENDING);
    assertThat(application.applicantUserId()).isEqualTo("user-1");
    assertThat(application.submittedAt()).isEqualTo(SUBMITTED_AT);
  }

  @ParameterizedTest
  @MethodSource("ineligibleApplicants")
  void disabledApplicantsCannotSubmit(AccountType accountType, AccountStatus accountStatus) {
    assertThatThrownBy(() -> submit("application-1", accountType, accountStatus))
        .isInstanceOf(IneligiblePromoterException.class);
  }

  @Test
  void pendingApplicationCanBeApprovedOnlyOnceEvenWhenReplayedOneHundredTimes() {
    PromotionApplication application = submit("application-1", AccountType.PERSONAL, AccountStatus.ENABLED);

    assertThat(application.approve("admin-1", DECIDED_AT).stateChanged()).isTrue();
    for (int replay = 0; replay < 100; replay++) {
      assertThat(application.approve("admin-1", DECIDED_AT).stateChanged()).isFalse();
    }

    assertThat(application.status()).isEqualTo(ApplicationStatus.APPROVED);
    assertThat(application.decidedBy()).contains("admin-1");
    assertThat(application.decidedAt()).contains(DECIDED_AT);
    assertThat(application.rejectionReason()).isEmpty();
  }

  @Test
  void decisionTimeCannotPrecedeSubmissionButMayEqualIt() {
    PromotionApplication application = submit(
        "application-1", AccountType.PERSONAL, AccountStatus.ENABLED);

    assertThatThrownBy(() -> application.approve("admin-1", SUBMITTED_AT.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(application.status()).isEqualTo(ApplicationStatus.PENDING);
    assertThat(application.approve("admin-1", SUBMITTED_AT).stateChanged()).isTrue();
    assertThat(application.decidedAt()).contains(SUBMITTED_AT);
  }

  @Test
  void rejectionRequiresAndPreservesReason() {
    PromotionApplication application = submit("application-1", AccountType.ENTERPRISE, AccountStatus.ENABLED);

    assertThatThrownBy(() -> application.reject("admin-1", "  ", DECIDED_AT))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(application.reject("admin-1", "资料不完整", DECIDED_AT).stateChanged()).isTrue();
    assertThat(application.reject("admin-2", "另一个原因", DECIDED_AT.plusSeconds(1)).stateChanged())
        .isFalse();
    assertThat(application.rejectionReason()).contains("资料不完整");
    assertThat(application.decidedBy()).contains("admin-1");
  }

  @Test
  void terminalDecisionCannotBeChangedToTheOtherOutcome() {
    PromotionApplication approved = submit("application-approved", AccountType.API, AccountStatus.ENABLED);
    approved.approve("admin-1", DECIDED_AT);

    assertThatThrownBy(() -> approved.reject("admin-1", "不能改写", DECIDED_AT.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);

    PromotionApplication rejected = submit("application-rejected", AccountType.API, AccountStatus.ENABLED);
    rejected.reject("admin-1", "不符合要求", DECIDED_AT);

    assertThatThrownBy(() -> rejected.approve("admin-1", DECIDED_AT.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectedApplicantCanCreateASeparateNewApplicationWithoutOverwritingHistory() {
    PromotionApplication rejected = submit("application-old", AccountType.PERSONAL, AccountStatus.ENABLED);
    rejected.reject("admin-1", "补充材料后重试", DECIDED_AT);

    PromotionApplication replacement = submit("application-new", AccountType.PERSONAL, AccountStatus.ENABLED);

    assertThat(rejected.id()).isEqualTo("application-old");
    assertThat(rejected.status()).isEqualTo(ApplicationStatus.REJECTED);
    assertThat(rejected.rejectionReason()).contains("补充材料后重试");
    assertThat(replacement.id()).isEqualTo("application-new");
    assertThat(replacement.status()).isEqualTo(ApplicationStatus.PENDING);
  }

  @Test
  void applicationPreservesTheCompleteApplicantSnapshot() {
    ApplicantSnapshot applicant = new ApplicantSnapshot(
        "user-1",
        Optional.of("https://assets.example/avatar.png"),
        Optional.of("推广用户"),
        Optional.of("13800000000"),
        Optional.of("user@example.com"),
        REGISTERED_AT,
        AccountType.ENTERPRISE,
        AccountStatus.ENABLED);

    PromotionApplication application = PromotionApplication.submit(
        "application-1", applicant, "申请理由", "内容渠道", true, SUBMITTED_AT);

    assertThat(application.applicantSnapshot()).isEqualTo(applicant);
    assertThat(application.applicantSnapshot().userId()).isEqualTo("user-1");
    assertThat(application.applicantSnapshot().avatarUrl())
        .contains("https://assets.example/avatar.png");
    assertThat(application.applicantSnapshot().nickname()).contains("推广用户");
    assertThat(application.applicantSnapshot().phone()).contains("13800000000");
    assertThat(application.applicantSnapshot().email()).contains("user@example.com");
    assertThat(application.applicantSnapshot().registeredAt()).isEqualTo(REGISTERED_AT);
    assertThat(application.applicantSnapshot().accountType()).isEqualTo(AccountType.ENTERPRISE);
    assertThat(application.applicantSnapshot().accountStatus()).isEqualTo(AccountStatus.ENABLED);
  }

  @Test
  void absentOptionalProfileFieldsStillAllowSubmission() {
    ApplicantSnapshot applicant = new ApplicantSnapshot(
        "user-1",
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        REGISTERED_AT,
        AccountType.API,
        AccountStatus.ENABLED);

    PromotionApplication application = PromotionApplication.submit(
        "application-1", applicant, "申请理由", "内容渠道", true, SUBMITTED_AT);

    assertThat(application.applicantSnapshot()).isEqualTo(applicant);
    assertThat(application.applicantSnapshot().avatarUrl()).isEmpty();
    assertThat(application.applicantSnapshot().nickname()).isEmpty();
    assertThat(application.applicantSnapshot().phone()).isEmpty();
    assertThat(application.applicantSnapshot().email()).isEmpty();
  }

  @Test
  void applicantSnapshotStringNeverExposesPhoneOrEmail() {
    ApplicantSnapshot applicant = new ApplicantSnapshot(
        "user-1",
        Optional.of("https://assets.example/avatar.png"),
        Optional.of("推广用户"),
        Optional.of("13800000000"),
        Optional.of("private@example.com"),
        REGISTERED_AT,
        AccountType.PERSONAL,
        AccountStatus.ENABLED);

    assertThat(applicant.toString())
        .doesNotContain("13800000000")
        .doesNotContain("private@example.com")
        .contains("REDACTED");
  }

  @Test
  void registrationTimeCannotBeAfterApplicationSubmission() {
    ApplicantSnapshot applicant = new ApplicantSnapshot(
        "user-1",
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        SUBMITTED_AT.plusSeconds(1),
        AccountType.PERSONAL,
        AccountStatus.ENABLED);

    assertThatThrownBy(() -> PromotionApplication.submit(
            "application-1", applicant, "申请理由", "内容渠道", true, SUBMITTED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("registeredAt");
  }

  @Test
  void submissionAndDecisionRejectMissingRequiredValues() {
    assertThatThrownBy(() -> PromotionApplication.submit(
            " ",
            applicant(AccountType.PERSONAL, AccountStatus.ENABLED),
            "申请理由",
            "内容渠道",
            true,
            SUBMITTED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PromotionApplication.submit(
            "application-1",
            new ApplicantSnapshot(
                " ",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                REGISTERED_AT,
                AccountType.PERSONAL,
                AccountStatus.ENABLED),
            "申请理由",
            "内容渠道",
            true,
            SUBMITTED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ApplicantSnapshot(
            "user-1",
            null,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            REGISTERED_AT,
            AccountType.PERSONAL,
            AccountStatus.ENABLED))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ApplicantSnapshot(
            "user-1",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            null,
            AccountType.PERSONAL,
            AccountStatus.ENABLED))
        .isInstanceOf(NullPointerException.class);

    ApplicantSnapshot applicant = applicant(AccountType.PERSONAL, AccountStatus.ENABLED);
    assertThatThrownBy(() -> PromotionApplication.submit(
            "application-1", applicant, " ", "内容渠道", true, SUBMITTED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PromotionApplication.submit(
            "application-1", applicant, "申请理由", " ", true, SUBMITTED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PromotionApplication.submit(
            "application-1", applicant, "申请理由", "内容渠道", true, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> PromotionApplication.submit(
            "application-1", applicant, "申请理由", "内容渠道", false, SUBMITTED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("agreement");

    PromotionApplication application = submit("application-1", AccountType.PERSONAL, AccountStatus.ENABLED);
    assertThatThrownBy(() -> application.approve(" ", DECIDED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> application.approve("admin-1", null))
        .isInstanceOf(NullPointerException.class);
  }

  private static PromotionApplication submit(
      String applicationId, AccountType accountType, AccountStatus accountStatus) {
    return PromotionApplication.submit(
        applicationId,
        applicant(accountType, accountStatus),
        "申请理由",
        "内容渠道",
        true,
        SUBMITTED_AT);
  }

  private static ApplicantSnapshot applicant(
      AccountType accountType, AccountStatus accountStatus) {
    return new ApplicantSnapshot(
        "user-1",
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        REGISTERED_AT,
        accountType,
        accountStatus);
  }

  private static Stream<AccountType> eligibleApplicants() {
    return Stream.of(AccountType.UNSET, AccountType.PERSONAL, AccountType.ENTERPRISE, AccountType.API);
  }

  private static Stream<Arguments> ineligibleApplicants() {
    return Stream.of(
        Arguments.of(AccountType.UNSET, AccountStatus.DISABLED),
        Arguments.of(AccountType.PERSONAL, AccountStatus.DISABLED),
        Arguments.of(AccountType.ENTERPRISE, AccountStatus.DISABLED),
        Arguments.of(AccountType.API, AccountStatus.DISABLED));
  }
}
