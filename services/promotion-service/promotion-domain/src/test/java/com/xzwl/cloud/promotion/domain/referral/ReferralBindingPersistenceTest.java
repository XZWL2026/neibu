package com.xzwl.cloud.promotion.domain.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReferralBindingPersistenceTest {

  private static final Instant REGISTERED_AT = Instant.parse("2026-08-30T04:00:00Z");
  private static final Instant CHANGED_AT = Instant.parse("2026-08-30T05:00:00Z");

  @Test
  void saveAndRehydrateRestoresExactReplayAfterCurrentVersionHasAdvanced() {
    ReferralBinding original = registrationBinding();
    AdminReferralBindingCommand oldCommand = command(
        "old-command", "user-p", "第一次改绑", CHANGED_AT, 1);
    ReferralBindingVersion oldResult = original.adminRebind(oldCommand).version();
    original.adminRebind(command(
        "newer-command", "user-q", "第二次改绑", CHANGED_AT.plusSeconds(1), 2));
    List<ReferralBindingVersion> persisted = original.versions();

    ReferralBinding rehydrated = ReferralBinding.rehydrate(persisted);
    ReferralBindingChangeResult replay = rehydrated.adminRebind(oldCommand);

    assertThat(replay.stateChanged()).isFalse();
    assertThat(replay.version()).isEqualTo(oldResult);
    assertThat(rehydrated.current()).isEqualTo(persisted.getLast());
    assertThat(rehydrated.versions()).containsExactlyElementsOf(persisted);

    ReferralBindingVersion appended = rehydrated.adminRebind(command(
        "post-rehydrate-command",
        "user-r",
        "重建后新增改绑",
        CHANGED_AT.plusSeconds(2),
        3)).version();

    assertThat(appended.versionNo()).isEqualTo(4);
    assertThat(rehydrated.current()).isEqualTo(appended);
  }

  @Test
  void rehydratedCommandIndexRejectsChangedSemanticsAndWrongEntryPoint() {
    ReferralBinding original = registrationBinding();
    AdminReferralBindingCommand persistedCommand = command(
        "stable-command", "user-p", "原始语义", CHANGED_AT, 1);
    original.adminRebind(persistedCommand);
    ReferralBinding rehydrated = ReferralBinding.rehydrate(original.versions());

    assertThatThrownBy(() -> rehydrated.adminRebind(command(
            "stable-command", "user-p", "被篡改的语义", CHANGED_AT, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("semantics");
    assertThatThrownBy(() -> rehydrated.adminBind(persistedCommand))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("action");
    assertThat(rehydrated.versions()).hasSize(2);
  }

  @Test
  void stableSemanticFingerprintIsDeterministicAndSensitiveToCompleteCommandSemantics() {
    AdminReferralBindingCommand command = command(
        "stable-command", "user-p", "原始语义", CHANGED_AT, 1);
    AdminReferralBindingCommand same = command(
        "stable-command", "user-p", "原始语义", CHANGED_AT, 1);
    AdminReferralBindingCommand changed = command(
        "stable-command", "user-p", "不同语义", CHANGED_AT, 1);
    ReferralLineage changedLineage = ReferralLineage.atRevision(9, "user-p");
    AdminReferralBindingCommand changedPrecondition = new AdminReferralBindingCommand(
        "stable-command",
        new DirectInviterFacts("user-p", true, null),
        changedLineage,
        new ReferralWritePrecondition(1, 9),
        "admin-1",
        "原始语义",
        CHANGED_AT);

    assertThat(command.semanticFingerprint())
        .matches("[0-9a-f]{64}")
        .isEqualTo(same.semanticFingerprint())
        .isNotEqualTo(changed.semanticFingerprint())
        .isNotEqualTo(changedPrecondition.semanticFingerprint());
  }

  @Test
  void rehydrateRejectsMalformedOrAmbiguousPersistedHistory() {
    ReferralBinding original = registrationBinding();
    original.adminRebind(command(
        "command-1", "user-p", "第一次改绑", CHANGED_AT, 1));
    original.adminRebind(command(
        "command-2", "user-q", "第二次改绑", CHANGED_AT.plusSeconds(1), 2));
    ReferralBindingVersion first = original.versions().get(0);
    ReferralBindingVersion second = original.versions().get(1);
    ReferralBindingVersion third = original.versions().get(2);

    assertThatThrownBy(() -> ReferralBinding.rehydrate(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReferralBinding.rehydrate(List.of(
            first, copy(second, 2, "different-user", second.effectiveFrom(),
                second.adminCommandReference(), second.writePrecondition()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("referredUserId");
    assertThatThrownBy(() -> ReferralBinding.rehydrate(List.of(
            first, copy(second, 3, second.referredUserId(), second.effectiveFrom(),
                second.adminCommandReference(), new ReferralWritePrecondition(2, 0)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("continuous");
    assertThatThrownBy(() -> ReferralBinding.rehydrate(List.of(
            first, copy(second, 2, second.referredUserId(), REGISTERED_AT.minusSeconds(1),
                second.adminCommandReference(), second.writePrecondition()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("effectiveFrom");
    assertThatThrownBy(() -> ReferralBinding.rehydrate(List.of(
            first,
            second,
            copy(third, 3, third.referredUserId(), third.effectiveFrom(),
                second.adminCommandReference(), third.writePrecondition()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique");
    assertThatThrownBy(() -> ReferralBinding.rehydrate(List.of(second)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void administratorVersionRequiresPersistableSha256Fingerprint() {
    assertThatThrownBy(() -> new ReferralBindingVersion(
            1,
            "user-b",
            "user-a",
            BenefitSnapshot.cashOnly("user-a"),
            REGISTERED_AT,
            ReferralBindingSource.ADMIN_BIND,
            new ReferralWritePrecondition(0, 0),
            "command",
            null,
            "admin-1",
            "管理员绑定"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("adminSemanticFingerprint");
  }

  @Test
  void versionRejectsSelfInvitationAndBenefitsThatCannotComeFromTheFixedResolver() {
    assertThatThrownBy(() -> registrationVersion(
            "user-b", "user-b", BenefitSnapshot.cashOnly("user-b"), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("self");
    assertThatThrownBy(() -> registrationVersion(
            "user-b", "user-a", BenefitSnapshot.cashOnly("user-p"), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LEVEL_1");
    assertThatThrownBy(() -> registrationVersion(
            "user-b", "user-a", BenefitSnapshot.cashOnly("user-b"), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("referredUserId");
    assertThatThrownBy(() -> registrationVersion(
            "user-d", "user-b", BenefitSnapshot.pointsAndCash("user-c", "user-a"), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LEVEL_2 point");
    assertThatThrownBy(() -> registrationVersion(
            "user-d", "user-b", BenefitSnapshot.pointsAndCash("user-b", "user-b"), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cash promoter");
    assertThatThrownBy(() -> registrationVersion(
            "user-d", "user-b", BenefitSnapshot.pointsAndCash("user-b", "user-d"), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("referredUserId");
    assertThatThrownBy(() -> registrationVersion(
            "user-d", "user-b", BenefitSnapshot.pointsOnly("user-c"), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NONE point");
    assertThatThrownBy(() -> registrationVersion(
            "user-d", "user-b", BenefitSnapshot.pointsOnly("user-d"), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("referredUserId");
  }

  @Test
  void rehydrateRejectsRepeatedOrDecreasingGraphRevisionsWithoutPoisoningValidHistory() {
    ReferralBindingVersion first = registrationVersion(
        "user-b", "user-a", BenefitSnapshot.cashOnly("user-a"), 10);
    ReferralBindingVersion second = administratorVersion(
        2, "user-b", "user-p", BenefitSnapshot.cashOnly("user-p"), 11, "command-1");
    ReferralBindingVersion repeated = administratorVersion(
        3, "user-b", "user-q", BenefitSnapshot.cashOnly("user-q"), 11, "command-2");
    ReferralBindingVersion decreasing = administratorVersion(
        3, "user-b", "user-q", BenefitSnapshot.cashOnly("user-q"), 9, "command-2");

    assertThatThrownBy(() -> ReferralBinding.rehydrate(List.of(first, second, repeated)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("graphRevision");
    assertThatThrownBy(() -> ReferralBinding.rehydrate(List.of(first, second, decreasing)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("graphRevision");

    ReferralBinding usable = ReferralBinding.rehydrate(List.of(first, second));
    assertThat(usable.versions()).containsExactly(first, second);
  }

  @Test
  void appendRejectsNonIncreasingGraphRevisionButExactReplayStillReturnsBeforeThatCheck() {
    ReferralBinding binding = ReferralBinding.bindAtRegistration(
        "user-b",
        new DirectInviterFacts("user-a", true, null),
        ReferralLineage.atRevision(10, "user-a"),
        new ReferralWritePrecondition(0, 10),
        REGISTERED_AT);
    AdminReferralBindingCommand repeatedGraph = commandAtRevision(
        "repeated-graph", "user-p", "重复图版本", CHANGED_AT, 1, 10);

    assertThatThrownBy(() -> binding.adminRebind(repeatedGraph))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("graphRevision");
    assertThat(binding.versions()).hasSize(1);

    AdminReferralBindingCommand firstCommand = commandAtRevision(
        "first-valid", "user-p", "首次有效变更", CHANGED_AT, 1, 11);
    ReferralBindingVersion firstResult = binding.adminRebind(firstCommand).version();
    binding.adminRebind(commandAtRevision(
        "second-valid", "user-q", "第二次有效变更", CHANGED_AT.plusSeconds(1), 2, 12));

    ReferralBindingChangeResult replay = binding.adminRebind(firstCommand);
    assertThat(replay.stateChanged()).isFalse();
    assertThat(replay.version()).isEqualTo(firstResult);
    assertThat(binding.versions()).hasSize(3);
  }

  @Test
  void ordinaryInviterSnapshotCannotIntroduceIndirectCashAndSelfInvitationStillFails() {
    for (String historicalCashOwner : java.util.List.of("user-b", "user-c")) {
      ReferralBinding binding = ReferralBinding.bindAtRegistration(
          "user-b", new DirectInviterFacts("user-a", true, null),
          ReferralLineage.atRevision(10, "user-a"), new ReferralWritePrecondition(0, 10), REGISTERED_AT);
      var ordinary = binding.adminRebind(commandWithFacts(
          "ordinary-inviter", new DirectInviterFacts("user-c", false, BenefitSnapshot.cashOnly(historicalCashOwner)),
          "普通邀请人仅返积分", 1, 11)).version();
      assertThat(ordinary.benefitSnapshot()).isEqualTo(BenefitSnapshot.pointsOnly("user-c"));
      assertThat(binding.versions()).hasSize(2);
      assertThatThrownBy(() -> binding.adminRebind(commandWithFacts(
          "self-invitation", new DirectInviterFacts("user-b", true, null), "禁止自邀", 2, 12)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(binding.versions()).hasSize(2);
      ReferralBindingVersion valid = binding.adminRebind(commandWithFacts(
          "valid-after-rejection", new DirectInviterFacts("user-p", true, null), "有效改绑", 2, 12)).version();
      assertThat(valid.versionNo()).isEqualTo(3);
      assertThat(valid.benefitSnapshot()).isEqualTo(BenefitSnapshot.cashOnly("user-p"));
    }
  }

  private static ReferralBinding registrationBinding() {
    return ReferralBinding.bindAtRegistration(
        "user-b",
        new DirectInviterFacts("user-a", true, null),
        ReferralLineage.of("user-a"),
        new ReferralWritePrecondition(0, 0),
        REGISTERED_AT);
  }

  private static AdminReferralBindingCommand command(
      String commandId,
      String inviterUserId,
      String reason,
      Instant effectiveFrom,
      long expectedAggregateVersionNo) {
    return commandAtRevision(
        commandId,
        inviterUserId,
        reason,
        effectiveFrom,
        expectedAggregateVersionNo,
        expectedAggregateVersionNo);
  }

  private static AdminReferralBindingCommand commandAtRevision(
      String commandId,
      String inviterUserId,
      String reason,
      Instant effectiveFrom,
      long expectedAggregateVersionNo,
      long graphRevision) {
    ReferralLineage lineage = ReferralLineage.atRevision(graphRevision, inviterUserId);
    return new AdminReferralBindingCommand(
        commandId,
        new DirectInviterFacts(inviterUserId, true, null),
        lineage,
        new ReferralWritePrecondition(expectedAggregateVersionNo, lineage.graphRevision()),
        "admin-1",
        reason,
        effectiveFrom);
  }

  private static AdminReferralBindingCommand commandWithFacts(
      String commandId,
      DirectInviterFacts facts,
      String reason,
      long expectedAggregateVersionNo,
      long graphRevision) {
    return new AdminReferralBindingCommand(
        commandId,
        facts,
        ReferralLineage.atRevision(graphRevision, facts.inviterUserId()),
        new ReferralWritePrecondition(expectedAggregateVersionNo, graphRevision),
        "admin-1",
        reason,
        CHANGED_AT);
  }

  private static ReferralBindingVersion registrationVersion(
      String referredUserId,
      String directInviterUserId,
      BenefitSnapshot snapshot,
      long graphRevision) {
    return new ReferralBindingVersion(
        1,
        referredUserId,
        directInviterUserId,
        snapshot,
        REGISTERED_AT,
        ReferralBindingSource.REGISTRATION,
        new ReferralWritePrecondition(0, graphRevision),
        null,
        null,
        null,
        null);
  }

  private static ReferralBindingVersion administratorVersion(
      long versionNo,
      String referredUserId,
      String directInviterUserId,
      BenefitSnapshot snapshot,
      long graphRevision,
      String commandId) {
    return new ReferralBindingVersion(
        versionNo,
        referredUserId,
        directInviterUserId,
        snapshot,
        CHANGED_AT.plusSeconds(versionNo),
        ReferralBindingSource.ADMIN_REBIND,
        new ReferralWritePrecondition(versionNo - 1, graphRevision),
        commandId,
        "0".repeat(64),
        "admin-1",
        "历史改绑");
  }

  private static ReferralBindingVersion copy(
      ReferralBindingVersion source,
      long versionNo,
      String referredUserId,
      Instant effectiveFrom,
      String commandId,
      ReferralWritePrecondition precondition) {
    return new ReferralBindingVersion(
        versionNo,
        referredUserId,
        source.directInviterUserId(),
        source.benefitSnapshot(),
        effectiveFrom,
        source.source(),
        precondition,
        commandId,
        source.adminSemanticFingerprint(),
        source.adminReference(),
        source.changeReason());
  }
}
