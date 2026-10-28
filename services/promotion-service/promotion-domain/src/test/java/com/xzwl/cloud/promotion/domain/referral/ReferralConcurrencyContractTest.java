package com.xzwl.cloud.promotion.domain.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReferralConcurrencyContractTest {

  private static final Instant REGISTERED_AT = Instant.parse("2026-08-30T04:00:00Z");
  private static final Instant CHANGED_AT = Instant.parse("2026-08-30T05:00:00Z");

  @Test
  void registrationAndAdministratorInitialBindExposeExpectedVersionZero() {
    ReferralWritePrecondition registrationPrecondition =
        new ReferralWritePrecondition(0, 40);
    ReferralBinding registration = ReferralBinding.bindAtRegistration(
        "user-b",
        new DirectInviterFacts("user-a", true, null),
        ReferralLineage.atRevision(40, "user-a"),
        registrationPrecondition,
        REGISTERED_AT);

    ReferralBinding adminBinding = ReferralBinding.unbound("user-c");
    ReferralWritePrecondition adminPrecondition = new ReferralWritePrecondition(0, 41);
    ReferralBindingVersion adminVersion = adminBinding.adminBind(command(
        "admin-bind", "user-p", ReferralLineage.atRevision(41, "user-p"),
        adminPrecondition, "管理员首次绑定")).version();

    assertThat(registration.current().writePrecondition())
        .isEqualTo(registrationPrecondition);
    assertThat(adminVersion.writePrecondition()).isEqualTo(adminPrecondition);
    assertThat(registration.current().versionNo())
        .isEqualTo(registrationPrecondition.expectedAggregateVersionNo() + 1);
    assertThat(adminVersion.versionNo())
        .isEqualTo(adminPrecondition.expectedAggregateVersionNo() + 1);
  }

  @Test
  void staleExpectedAggregateVersionIsRejectedWithoutPollutingHistory() {
    ReferralBinding binding = registrationBinding("user-b", 50);

    assertThatThrownBy(() -> binding.adminRebind(command(
            "stale-command",
            "user-p",
            ReferralLineage.atRevision(51, "user-p"),
            new ReferralWritePrecondition(0, 51),
            "陈旧聚合版本")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedAggregateVersionNo");
    assertThat(binding.versions()).hasSize(1);
  }

  @Test
  void independentInstancesFromOneVersionProduceTheSameRepositoryCasPrecondition() {
    List<ReferralBindingVersion> persisted = registrationBinding("user-b", 60).versions();
    ReferralBinding firstInstance = ReferralBinding.rehydrate(persisted);
    ReferralBinding secondInstance = ReferralBinding.rehydrate(persisted);
    ReferralWritePrecondition sharedPrecondition = new ReferralWritePrecondition(1, 61);

    ReferralBindingVersion firstWrite = firstInstance.adminRebind(command(
        "first-command", "user-p", ReferralLineage.atRevision(61, "user-p"),
        sharedPrecondition, "实例一写入")).version();
    ReferralBindingVersion secondWrite = secondInstance.adminRebind(command(
        "second-command", "user-q", ReferralLineage.atRevision(61, "user-q"),
        sharedPrecondition, "实例二写入")).version();

    assertThat(firstWrite.writePrecondition()).isEqualTo(sharedPrecondition);
    assertThat(secondWrite.writePrecondition()).isEqualTo(sharedPrecondition);
    assertThat(firstWrite.versionNo()).isEqualTo(2);
    assertThat(secondWrite.versionNo()).isEqualTo(2);
  }

  @Test
  void oppositeReferralDecisionsAtOneGraphRevisionRequireOuterCasToAcceptOnlyOne() {
    long sharedGraphRevision = 70;
    ReferralBinding userB = registrationBinding("user-b", sharedGraphRevision - 1);
    ReferralBinding userC = registrationBinding("user-c", sharedGraphRevision - 1);

    ReferralBindingVersion bToC = userB.adminRebind(command(
        "b-to-c",
        "user-c",
        ReferralLineage.atRevision(sharedGraphRevision, "user-c", "user-a"),
        new ReferralWritePrecondition(1, sharedGraphRevision),
        "B 改绑到 C")).version();
    ReferralBindingVersion cToB = userC.adminRebind(command(
        "c-to-b",
        "user-b",
        ReferralLineage.atRevision(sharedGraphRevision, "user-b", "user-a"),
        new ReferralWritePrecondition(1, sharedGraphRevision),
        "C 改绑到 B")).version();

    RepositoryGraphCas repository = new RepositoryGraphCas(sharedGraphRevision);
    assertThat(repository.tryCommit(bToC)).isTrue();
    assertThat(repository.tryCommit(cToB)).isFalse();
    assertThat(bToC.writePrecondition().expectedGraphRevision())
        .isEqualTo(cToB.writePrecondition().expectedGraphRevision());
  }

  @Test
  void preconditionAndLineageRequireNonNegativeMatchingGraphRevisions() {
    assertThatThrownBy(() -> new ReferralWritePrecondition(-1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReferralWritePrecondition(0, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ReferralLineage.atRevision(-1, "user-a"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> command(
            "mismatch", "user-p", ReferralLineage.atRevision(80, "user-p"),
            new ReferralWritePrecondition(1, 81), "图版本不匹配"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("graphRevision");
  }

  private static ReferralBinding registrationBinding(String referredUserId, long graphRevision) {
    return ReferralBinding.bindAtRegistration(
        referredUserId,
        new DirectInviterFacts("user-a", true, null),
        ReferralLineage.atRevision(graphRevision, "user-a"),
        new ReferralWritePrecondition(0, graphRevision),
        REGISTERED_AT);
  }

  private static AdminReferralBindingCommand command(
      String commandId,
      String inviterUserId,
      ReferralLineage lineage,
      ReferralWritePrecondition precondition,
      String reason) {
    return new AdminReferralBindingCommand(
        commandId,
        new DirectInviterFacts(inviterUserId, true, null),
        lineage,
        precondition,
        "admin-1",
        reason,
        CHANGED_AT);
  }

  private static final class RepositoryGraphCas {

    private final AtomicLong graphRevision;

    private RepositoryGraphCas(long initialGraphRevision) {
      this.graphRevision = new AtomicLong(initialGraphRevision);
    }

    private boolean tryCommit(ReferralBindingVersion version) {
      long expected = version.writePrecondition().expectedGraphRevision();
      return graphRevision.compareAndSet(expected, expected + 1);
    }
  }
}
