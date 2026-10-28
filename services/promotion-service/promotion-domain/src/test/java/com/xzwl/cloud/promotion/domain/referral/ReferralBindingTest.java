package com.xzwl.cloud.promotion.domain.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class ReferralBindingTest {

  private static final Instant REGISTERED_AT = Instant.parse("2026-08-30T04:00:00Z");
  private static final Instant CHANGED_AT = Instant.parse("2026-08-30T05:00:00Z");

  @Test
  void registrationBindingCreatesImmutableVersionOneFromResolver() {
    ReferralBinding binding = ReferralBinding.bindAtRegistration(
        "user-b",
        new DirectInviterFacts("user-a", true, null),
        ReferralLineage.of("user-a"),
        new ReferralWritePrecondition(0, 0),
        REGISTERED_AT);

    ReferralBindingVersion version = binding.current();
    assertThat(binding.referredUserId()).isEqualTo("user-b");
    assertThat(version.versionNo()).isEqualTo(1L);
    assertThat(version.referredUserId()).isEqualTo("user-b");
    assertThat(version.directInviterUserId()).isEqualTo("user-a");
    assertThat(version.benefitSnapshot())
        .isEqualTo(BenefitSnapshot.cashOnly("user-a"));
    assertThat(version.effectiveFrom()).isEqualTo(REGISTERED_AT);
    assertThat(version.source()).isEqualTo(ReferralBindingSource.REGISTRATION);
    assertThat(version.adminId()).isEmpty();
    assertThat(version.reason()).isEmpty();
  }

  @Test
  void administratorInitialBindingAndRebindsAppendAuditedContinuousHistory() {
    ReferralBinding binding = ReferralBinding.unbound("user-c");
    ReferralBindingChangeResult initial = binding.adminBind(adminCommand(
        "command-1", "user-b", false,
        BenefitSnapshot.cashOnly("user-a"),
        ReferralLineage.of("user-b", "user-a"),
        "首次人工绑定", CHANGED_AT, 0));
    ReferralBindingVersion versionOne = initial.version();

    ReferralBindingChangeResult second = binding.adminRebind(adminCommand(
        "command-2", "user-d", false,
        BenefitSnapshot.pointsOnly("user-c"),
        ReferralLineage.of("user-d", "user-a"),
        "依据申诉改绑", CHANGED_AT.plusSeconds(60)));
    ReferralBindingChangeResult third = binding.adminRebind(adminCommand(
        "command-3", "user-e", true, null,
        ReferralLineage.of("user-e"),
        "再次依据申诉改绑", CHANGED_AT.plusSeconds(120), 2));

    assertThat(initial.stateChanged()).isTrue();
    assertThat(versionOne.source()).isEqualTo(ReferralBindingSource.ADMIN_BIND);
    assertThat(versionOne.adminId()).contains("admin-1");
    assertThat(versionOne.reason()).contains("首次人工绑定");
    assertThat(second.version().versionNo()).isEqualTo(2L);
    assertThat(second.version().source()).isEqualTo(ReferralBindingSource.ADMIN_REBIND);
    assertThat(third.version().versionNo()).isEqualTo(3L);
    assertThat(binding.versions()).containsExactly(versionOne, second.version(), third.version());
    assertThat(binding.current()).isEqualTo(third.version());
  }

  @Test
  void everyAdministratorChangeUsesTheResolverInsteadOfAcceptingCashTierInput() {
    ReferralBinding binding = registrationBinding();

    ReferralBindingVersion ordinaryInviter = binding.adminRebind(adminCommand(
        "ordinary-command", "user-c", false,
        BenefitSnapshot.cashOnly("user-a"),
        ReferralLineage.of("user-c", "user-a"),
        "改绑普通邀请人", CHANGED_AT)).version();
    ReferralBindingVersion promoterInviter = binding.adminRebind(adminCommand(
        "promoter-command", "user-p", true,
        BenefitSnapshot.pointsOnly("someone-else"),
        ReferralLineage.of("user-p"),
        "改绑审核推广人", CHANGED_AT.plusSeconds(1), 2)).version();

    assertThat(ordinaryInviter.benefitSnapshot()).isEqualTo(BenefitSnapshot.pointsOnly("user-c"));
    assertThat(promoterInviter.benefitSnapshot()).isEqualTo(BenefitSnapshot.cashOnly("user-p"));
  }

  @Test
  void exactAdministratorCommandReplayReturnsExistingVersionWithoutAppending() {
    ReferralBinding binding = registrationBinding();
    AdminReferralBindingCommand command = adminCommand(
        "stable-request-id", "user-p", true, null,
        ReferralLineage.of("user-p"), "申诉改绑", CHANGED_AT);

    ReferralBindingChangeResult first = binding.adminRebind(command);
    ReferralBindingChangeResult replay = binding.adminRebind(command);

    assertThat(first.stateChanged()).isTrue();
    assertThat(replay.stateChanged()).isFalse();
    assertThat(replay.version()).isSameAs(first.version());
    assertThat(binding.versions()).hasSize(2);
  }

  @Test
  void adminBindCommandCannotReplayThroughAdminRebindEntryPoint() {
    ReferralBinding binding = ReferralBinding.unbound("user-b");
    AdminReferralBindingCommand command = adminCommand(
        "bind-command", "user-p", true, null,
        ReferralLineage.of("user-p"), "首次人工绑定", CHANGED_AT, 0);
    binding.adminBind(command);

    assertThatThrownBy(() -> binding.adminRebind(command))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("action");
    assertThat(binding.versions()).hasSize(1);
  }

  @Test
  void adminRebindCommandCannotReplayThroughAdminBindEntryPoint() {
    ReferralBinding binding = registrationBinding();
    AdminReferralBindingCommand command = adminCommand(
        "rebind-command", "user-p", true, null,
        ReferralLineage.of("user-p"), "管理员改绑", CHANGED_AT);
    binding.adminRebind(command);

    assertThatThrownBy(() -> binding.adminBind(command))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("action");
    assertThat(binding.versions()).hasSize(2);
  }

  @Test
  void semanticallyDifferentAdministratorCommandsAppendEvenForSameInviter() {
    ReferralBinding binding = registrationBinding();

    ReferralBindingVersion first = binding.adminRebind(adminCommand(
        "request-1", "user-p", true, null,
        ReferralLineage.of("user-p"), "第一份材料", CHANGED_AT)).version();
    ReferralBindingVersion second = binding.adminRebind(adminCommand(
        "request-2", "user-p", true, null,
        ReferralLineage.of("user-p"), "补充材料", CHANGED_AT, 2)).version();

    assertThat(first.versionNo()).isEqualTo(2L);
    assertThat(second.versionNo()).isEqualTo(3L);
    assertThat(binding.versions()).hasSize(3);
  }

  @Test
  void reusingACommandIdWithDifferentSemanticsIsRejectedWithoutMutation() {
    ReferralBinding binding = registrationBinding();
    binding.adminRebind(adminCommand(
        "request-1", "user-p", true, null,
        ReferralLineage.of("user-p"), "第一份材料", CHANGED_AT));

    assertThatThrownBy(() -> binding.adminRebind(adminCommand(
            "request-1", "user-q", true, null,
            ReferralLineage.of("user-q"), "不同材料", CHANGED_AT.plusSeconds(1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commandId");
    assertThat(binding.versions()).hasSize(2);
  }

  @Test
  void selfInvitationAndLoadedLineageCyclesAreRejectedBeforeVersionAppend() {
    assertThatThrownBy(() -> ReferralBinding.bindAtRegistration(
            "user-b",
            new DirectInviterFacts("user-b", false, null),
            ReferralLineage.of("user-b"),
            new ReferralWritePrecondition(0, 0),
            REGISTERED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");

    ReferralBinding binding = registrationBinding();
    assertThatThrownBy(() -> binding.adminRebind(adminCommand(
            "cycle-command", "user-c", false, null,
            ReferralLineage.of("user-c", "user-b", "user-a"),
            "构成 B-C-B 环", CHANGED_AT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");
    assertThat(binding.versions()).hasSize(1);
  }

  @Test
  void directInviterFactsMustMatchTheCallerLoadedLineage() {
    ReferralBinding binding = registrationBinding();

    assertThatThrownBy(() -> binding.adminRebind(adminCommand(
            "mismatch-command", "user-c", false, null,
            ReferralLineage.of("different-user"), "输入不一致", CHANGED_AT)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lineage");
    assertThat(binding.versions()).hasSize(1);
  }

  @Test
  void changeTimeCannotPrecedeCurrentVersionAndValidationHappensBeforeAppend() {
    ReferralBinding binding = registrationBinding();

    assertThatThrownBy(() -> binding.adminRebind(adminCommand(
            "old-command", "user-p", true, null,
            ReferralLineage.of("user-p"), "时间倒退", REGISTERED_AT.minusSeconds(1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("effectiveFrom");
    assertThat(binding.versions()).hasSize(1);
  }

  @Test
  void exposedVersionsAndLineageAncestorsAreImmutableCopies() {
    ReferralBinding binding = registrationBinding();
    List<ReferralBindingVersion> versions = binding.versions();
    ReferralLineage lineage = ReferralLineage.of("user-c", "user-b", "user-a");
    Set<String> ancestors = lineage.ancestorUserIds();

    assertThatThrownBy(() -> versions.add(binding.current()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ancestors.add("user-z"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(binding.versions()).hasSize(1);
    assertThat(lineage.ancestorUserIds()).containsExactlyInAnyOrder("user-b", "user-a");
  }

  @Test
  void twelveConcurrentDistinctRebindsReloadAfterConflictsAndKeepLinearizedHistory()
      throws Exception {
    ReferralBinding binding = registrationBinding();
    int changeCount = 12;
    CyclicBarrier start = new CyclicBarrier(changeCount);
    ExecutorService executor = Executors.newFixedThreadPool(changeCount);
    List<Future<ReferralBindingChangeResult>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < changeCount; index++) {
        int target = index;
        futures.add(executor.submit(() -> {
          start.await();
          String inviter = "promoter-" + target;
          while (true) {
            ReferralBindingVersion observed = binding.current();
            long graphRevision = observed.writePrecondition().expectedGraphRevision() + 1;
            try {
              return binding.adminRebind(adminCommand(
                  "concurrent-command-" + target,
                  inviter,
                  true,
                  null,
                  ReferralLineage.atRevision(graphRevision, inviter),
                  "并发改绑-" + target,
                  CHANGED_AT,
                  observed.versionNo()));
            } catch (IllegalArgumentException exception) {
              if (!exception.getMessage().contains("expectedAggregateVersionNo")) {
                throw exception;
              }
            }
          }
        }));
      }
      for (Future<ReferralBindingChangeResult> future : futures) {
        assertThat(future.get().stateChanged()).isTrue();
      }
    } finally {
      executor.shutdownNow();
    }

    assertThat(binding.versions())
        .extracting(ReferralBindingVersion::versionNo)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, changeCount + 1L).boxed().toList());
    assertThat(binding.versions())
        .extracting(version -> version.writePrecondition().expectedGraphRevision())
        .containsExactlyElementsOf(LongStream.rangeClosed(0, changeCount).boxed().toList());
    assertThat(binding.versions()).hasSize(changeCount + 1);
    assertThat(binding.current()).isEqualTo(binding.versions().getLast());
  }

  @Test
  void concurrentReplayOfOneCommandAppendsAtMostOneVersion() throws Exception {
    ReferralBinding binding = registrationBinding();
    AdminReferralBindingCommand command = adminCommand(
        "shared-command", "user-p", true, null,
        ReferralLineage.of("user-p"), "同一命令并发重放", CHANGED_AT);
    int replayCount = 16;
    CyclicBarrier start = new CyclicBarrier(replayCount);
    ExecutorService executor = Executors.newFixedThreadPool(replayCount);
    List<Future<ReferralBindingChangeResult>> futures = new ArrayList<>();
    try {
      for (int replay = 0; replay < replayCount; replay++) {
        futures.add(executor.submit(() -> {
          start.await();
          return binding.adminRebind(command);
        }));
      }

      List<ReferralBindingChangeResult> results = new ArrayList<>();
      for (Future<ReferralBindingChangeResult> future : futures) {
        results.add(future.get());
      }
      assertThat(results).filteredOn(ReferralBindingChangeResult::stateChanged).hasSize(1);
      assertThat(results).extracting(ReferralBindingChangeResult::version)
          .containsOnly(binding.current());
    } finally {
      executor.shutdownNow();
    }

    assertThat(binding.versions()).hasSize(2);
  }

  @Test
  void historicalVersionRemainsUnchangedWhenInviterLaterBecomesPromoter() {
    ReferralBinding b = ReferralBinding.bindAtRegistration(
        "user-b", new DirectInviterFacts("user-a", true, null),
        ReferralLineage.of("user-a"), new ReferralWritePrecondition(0, 0), REGISTERED_AT);
    ReferralBinding c = ReferralBinding.bindAtRegistration(
        "user-c", new DirectInviterFacts("user-b", false, b.current().benefitSnapshot()),
        ReferralLineage.of("user-b", "user-a"), new ReferralWritePrecondition(0, 0),
        REGISTERED_AT.plusSeconds(1));
    ReferralBindingVersion historicalC = c.current();

    ReferralBinding e = ReferralBinding.bindAtRegistration(
        "user-e", new DirectInviterFacts("user-b", true, b.current().benefitSnapshot()),
        ReferralLineage.of("user-b", "user-a"), new ReferralWritePrecondition(0, 0),
        REGISTERED_AT.plusSeconds(2));
    ReferralBinding f = ReferralBinding.bindAtRegistration(
        "user-f", new DirectInviterFacts("user-e", false, e.current().benefitSnapshot()),
        ReferralLineage.of("user-e", "user-b", "user-a"),
        new ReferralWritePrecondition(0, 0), REGISTERED_AT.plusSeconds(3));

    assertThat(c.current()).isSameAs(historicalC);
    assertThat(historicalC.benefitSnapshot()).isEqualTo(BenefitSnapshot.pointsOnly("user-b"));
    assertThat(e.current().benefitSnapshot()).isEqualTo(BenefitSnapshot.cashOnly("user-b"));
    assertThat(f.current().benefitSnapshot()).isEqualTo(BenefitSnapshot.pointsOnly("user-e"));
  }

  @Test
  void sourceVersionCommandAndLineageBoundariesRejectInvalidInputs() {
    assertThatThrownBy(() -> ReferralBinding.unbound(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReferralLineage.of(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReferralLineage.of("user-a", "user-a"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AdminReferralBindingCommand(
            " ", new DirectInviterFacts("user-a", true, null), ReferralLineage.of("user-a"),
            new ReferralWritePrecondition(1, 0), "admin-1", "原因", CHANGED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AdminReferralBindingCommand(
            "command", new DirectInviterFacts("user-a", true, null),
            ReferralLineage.of("user-a"), new ReferralWritePrecondition(1, 0),
            " ", "原因", CHANGED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AdminReferralBindingCommand(
            "command", new DirectInviterFacts("user-a", true, null),
            ReferralLineage.of("user-a"), new ReferralWritePrecondition(1, 0),
            "admin-1", " ", CHANGED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AdminReferralBindingCommand(
            "command", new DirectInviterFacts("user-a", true, null),
            ReferralLineage.of("user-a"), new ReferralWritePrecondition(1, 0),
            "admin-1", "原因", null))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> new ReferralBindingVersion(
            2, "user-b", "user-a", BenefitSnapshot.pointsOnly("user-a"), REGISTERED_AT,
            ReferralBindingSource.REGISTRATION, new ReferralWritePrecondition(1, 0),
            null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReferralBindingVersion(
            1, "user-b", "user-a", BenefitSnapshot.pointsOnly("user-a"), REGISTERED_AT,
            ReferralBindingSource.REGISTRATION, new ReferralWritePrecondition(0, 0),
            "command", "0".repeat(64), "admin", "reason"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReferralBindingVersion(
            1, "user-b", "user-a", BenefitSnapshot.pointsOnly("user-a"), REGISTERED_AT,
            ReferralBindingSource.ADMIN_BIND, new ReferralWritePrecondition(0, 0),
            "command", "0".repeat(64), " ", "reason"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReferralBindingVersion(
            1, "user-b", "user-a", BenefitSnapshot.pointsOnly("user-a"), REGISTERED_AT,
            null, new ReferralWritePrecondition(0, 0), null, null, null, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aggregateVersionCommandAndLineageTextNeverLeakRealIdentifiers() {
    String referred = "secret-referred-123";
    String inviter = "secret-inviter-456";
    String admin = "secret-admin-789";
    String commandId = "secret-command-012";
    ReferralBinding binding = ReferralBinding.unbound(referred);
    AdminReferralBindingCommand command = new AdminReferralBindingCommand(
        commandId,
        new DirectInviterFacts(inviter, true, null),
        ReferralLineage.of(inviter, "secret-ancestor-345"),
        new ReferralWritePrecondition(0, 0),
        admin,
        "人工纠正",
        CHANGED_AT);
    ReferralBindingVersion version = binding.adminBind(command).version();

    assertThat(binding.toString()).doesNotContain(referred, inviter, admin, commandId);
    assertThat(version.toString()).doesNotContain(referred, inviter, admin, commandId);
    assertThat(command.toString()).doesNotContain(referred, inviter, admin, commandId);
    assertThat(command.lineage().toString()).doesNotContain(inviter, "secret-ancestor-345");
  }

  private static ReferralBinding registrationBinding() {
    return ReferralBinding.bindAtRegistration(
        "user-b",
        new DirectInviterFacts("user-a", true, null),
        ReferralLineage.of("user-a"),
        new ReferralWritePrecondition(0, 0),
        REGISTERED_AT);
  }

  private static AdminReferralBindingCommand adminCommand(
      String commandId,
      String inviterUserId,
      boolean approvedPromoter,
      BenefitSnapshot existingBenefitSnapshot,
      ReferralLineage lineage,
      String reason,
      Instant effectiveFrom) {
    return adminCommand(
        commandId,
        inviterUserId,
        approvedPromoter,
        existingBenefitSnapshot,
        lineage,
        reason,
        effectiveFrom,
        1);
  }

  private static AdminReferralBindingCommand adminCommand(
      String commandId,
      String inviterUserId,
      boolean approvedPromoter,
      BenefitSnapshot existingBenefitSnapshot,
      ReferralLineage lineage,
      String reason,
      Instant effectiveFrom,
      long expectedAggregateVersionNo) {
    long graphRevision = Math.max(
        lineage.graphRevision(), expectedAggregateVersionNo);
    ReferralLineage currentLineage = ReferralLineage.atRevision(
        graphRevision,
        lineage.directInviterUserId(),
        lineage.ancestorUserIds().toArray(String[]::new));
    return new AdminReferralBindingCommand(
        commandId,
        new DirectInviterFacts(inviterUserId, approvedPromoter, existingBenefitSnapshot),
        currentLineage,
        new ReferralWritePrecondition(expectedAggregateVersionNo, graphRevision),
        "admin-1",
        reason,
        effectiveFrom);
  }
}
