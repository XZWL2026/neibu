package com.xzwl.cloud.promotion.domain.reward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PointRewardIntegrityTest {

  private static final Instant NOW = Instant.parse("2026-08-30T13:00:00Z");

  @Test
  void rehydrateRestoresPositionsAndStableCompoundReplay() {
    PointRewardAccount source = accountWithDebt();
    PointRewardWriteResult original = grant(source, "future", "entitlement-2", "7");

    PointRewardAccount restored = PointRewardAccount.rehydrate("beneficiary", source.entries());
    PointRewardWriteResult replay = grant(restored, "future", "entitlement-2", "7");

    assertThat(restored.currentSnapshot()).isEqualTo(source.currentSnapshot());
    assertThat(replay.entries()).containsExactlyElementsOf(original.entries());
    assertThat(replay.netRewardToCredit()).isEqualTo(original.netRewardToCredit());
    assertThat(restored.entries()).hasSize(source.entries().size());
  }

  @Test
  void rehydrateRejectsSequenceBeneficiaryChainDuplicateKeyAndDuplicateEntitlement() {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");
    ReferralPointEntitlement granted = grant(account, "grant", "entitlement", "5").entries().getFirst();

    assertThatThrownBy(
            () ->
                PointRewardAccount.rehydrate(
                    "beneficiary", List.of(copy(granted, 2, granted.before(), granted.after(), "grant", "entitlement"))))
        .hasMessageContaining("sequence");
    assertThatThrownBy(() -> PointRewardAccount.rehydrate("other", List.of(granted)))
        .hasMessageContaining("beneficiary");

    ReferralPointEntitlement brokenChain =
        new ReferralPointEntitlement(
            2,
            "beneficiary",
            PointRewardActionType.POINT_REWARD_GRANTED,
            amount("1"),
            PointRewardSnapshot.zero(),
            new PointRewardSnapshot(amount("1"), Decimal4.ZERO, Decimal4.ZERO),
            "grant-2",
            "entitlement-2",
            "settlement",
            "allocation-entitlement-2",
            "funding-lot",
            "consumer",
            8,
            CashTier.NONE,
            null,
            NOW,
            PointRewardReason.TASK_SUCCESS_REWARD_GRANTED);
    assertThatThrownBy(() -> PointRewardAccount.rehydrate("beneficiary", List.of(granted, brokenChain)))
        .hasMessageContaining("chain");

    ReferralPointEntitlement duplicateKey =
        copy(
            granted,
            2,
            granted.after(),
            new PointRewardSnapshot(amount("10"), Decimal4.ZERO, Decimal4.ZERO),
            "grant",
            "entitlement-2");
    assertThatThrownBy(() -> PointRewardAccount.rehydrate("beneficiary", List.of(granted, duplicateKey)))
        .hasMessageContaining("idempotency");

    ReferralPointEntitlement duplicateEntitlement =
        copy(
            granted,
            2,
            granted.after(),
            new PointRewardSnapshot(amount("10"), Decimal4.ZERO, Decimal4.ZERO),
            "grant-2",
            "entitlement");
    assertThatThrownBy(
            () -> PointRewardAccount.rehydrate("beneficiary", List.of(granted, duplicateEntitlement)))
        .hasMessageContaining("entitlement");
  }

  @Test
  void rehydrateRejectsMissingTamperedOrOrphanDebtFollowUp() {
    PointRewardAccount debtAccount = accountWithDebt();
    List<ReferralPointEntitlement> history = debtAccount.entries();
    List<ReferralPointEntitlement> missingDebt = new ArrayList<>(history);
    ReferralPointEntitlement debt = missingDebt.removeLast();
    assertThat(debt.actionType()).isEqualTo(PointRewardActionType.POINT_DEBT_CREATED);
    assertThatThrownBy(() -> PointRewardAccount.rehydrate("beneficiary", missingDebt))
        .hasMessageContaining("follow-up");

    List<ReferralPointEntitlement> tampered = new ArrayList<>(history);
    tampered.set(
        tampered.size() - 1,
        new ReferralPointEntitlement(
            debt.sequenceNo(),
            debt.beneficiaryUserId(),
            debt.actionType(),
            amount("5.9999"),
            debt.before(),
            debt.before().debtCreated(amount("5.9999")),
            debt.sourceEventId(),
            debt.rewardEntitlementId(),
            debt.sourceSettlementId(),
            debt.sourceAllocationId(),
            debt.sourceFundingLotId(),
            debt.sourceWalletOwnerUserId(),
            debt.referralBindingVersion(),
            debt.cashTier(),
            debt.recoveredNow4(),
            debt.createdAt(),
            debt.reason()));
    assertThatThrownBy(() -> PointRewardAccount.rehydrate("beneficiary", tampered))
        .hasMessageContaining("follow-up");

    ReferralPointEntitlement grant = history.getFirst();
    ReferralPointEntitlement orphan =
        new ReferralPointEntitlement(
            2,
            "beneficiary",
            PointRewardActionType.POINT_DEBT_CREATED,
            amount("1"),
            grant.after(),
            grant.after().debtCreated(amount("1")),
            "orphan",
            "entitlement",
            "settlement",
            "allocation-entitlement",
            "funding-lot",
            "consumer",
            8,
            CashTier.NONE,
            null,
            NOW,
            PointRewardReason.REWARD_REVERSAL_SHORTFALL);
    assertThatThrownBy(() -> PointRewardAccount.rehydrate("beneficiary", List.of(grant, orphan)))
        .hasMessageContaining("orphan");
  }

  @Test
  void rehydrateRejectsMissingOrWrongDebtOffsetFollowUp() {
    PointRewardAccount account = accountWithDebt();
    grant(account, "future", "entitlement-2", "7");
    List<ReferralPointEntitlement> history = account.entries();
    List<ReferralPointEntitlement> missingOffset = new ArrayList<>(history);
    ReferralPointEntitlement offset = missingOffset.removeLast();
    assertThat(offset.actionType()).isEqualTo(PointRewardActionType.POINT_DEBT_OFFSET);
    assertThatThrownBy(() -> PointRewardAccount.rehydrate("beneficiary", missingOffset))
        .hasMessageContaining("follow-up");

    List<ReferralPointEntitlement> wrongOrder = new ArrayList<>(history);
    ReferralPointEntitlement primary = wrongOrder.get(wrongOrder.size() - 2);
    wrongOrder.set(wrongOrder.size() - 2, offset);
    wrongOrder.set(wrongOrder.size() - 1, primary);
    assertThatThrownBy(() -> PointRewardAccount.rehydrate("beneficiary", wrongOrder))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void entryConstructionValidatesActionsReasonsTransitionsIdsAndRedactsSources() {
    PointRewardAccount account = PointRewardAccount.create("beneficiary-secret");
    ReferralPointEntitlement fact = grant(account, "event-secret", "entitlement-secret", "1").entries().getFirst();

    assertThat(fact.toString())
        .doesNotContain("beneficiary-secret", "event-secret", "entitlement-secret", "consumer");
    assertThatThrownBy(
            () ->
                new ReferralPointEntitlement(
                    fact.sequenceNo(),
                    fact.beneficiaryUserId(),
                    fact.actionType(),
                    fact.points4(),
                    fact.before(),
                    fact.after(),
                    fact.sourceEventId(),
                    fact.rewardEntitlementId(),
                    fact.sourceSettlementId(),
                    fact.sourceAllocationId(),
                    fact.sourceFundingLotId(),
                    fact.sourceWalletOwnerUserId(),
                    fact.referralBindingVersion(),
                    fact.cashTier(),
                    fact.recoveredNow4(),
                    fact.createdAt(),
                    PointRewardReason.TASK_SUCCESS_REWARD_REVERSED))
        .hasMessageContaining("reason");

    String longId = "x".repeat(201);
    assertThatThrownBy(() -> PointRewardAccount.create(longId))
        .hasMessageContaining("too long")
        .hasMessageNotContaining(longId);
    assertThatThrownBy(
            () ->
                account.grant(
                    longId,
                    "entitlement-2",
                    "settlement",
                    "allocation-2",
                    "funding-lot",
                    "consumer",
                    8,
                    CashTier.NONE,
                    amount("1"),
                    NOW))
        .hasMessageContaining("too long")
        .hasMessageNotContaining(longId);
  }

  @Test
  void replayOneHundredTimesDoesNotAppendOrChangeProjection() {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");
    PointRewardWriteResult first = grant(account, "grant", "entitlement", "2");

    for (int index = 0; index < 100; index++) {
      assertThat(grant(account, "grant", "entitlement", "2").entries())
          .containsExactlyElementsOf(first.entries());
    }

    assertThat(account.entries()).hasSize(1);
    assertThat(account.currentSnapshot().cumulativeGranted()).isEqualTo(amount("2"));
  }

  @Test
  void writeResultRejectsNetAmountThatDoesNotMatchItsImmutableFacts() {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");
    PointRewardWriteResult valid = grant(account, "grant", "entitlement", "2");

    assertThatThrownBy(
            () ->
                PointRewardWriteResult.fromLedgerFacts(
                    valid.entries(), valid.snapshot(), amount("999")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("netRewardToCredit");
  }

  @Test
  void writeResultRejectsTruncatedReorderedAndAppendedCompoundFacts() {
    PointRewardAccount account = accountWithDebt();
    PointRewardWriteResult valid = grant(account, "future", "entitlement-2", "9");
    ReferralPointEntitlement primary = valid.entries().getFirst();
    ReferralPointEntitlement offset = valid.entries().getLast();

    assertThatThrownBy(
            () ->
                PointRewardWriteResult.fromLedgerFacts(
                    List.of(primary), primary.after(), amount("9")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("follow-up");
    assertThatThrownBy(
            () ->
                PointRewardWriteResult.fromLedgerFacts(
                    List.of(offset, primary), primary.after(), Decimal4.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("primary");

    ReferralPointEntitlement unrelated =
        grant(account, "unrelated", "entitlement-3", "1").entries().getFirst();
    assertThatThrownBy(
            () ->
                PointRewardWriteResult.fromLedgerFacts(
                    List.of(primary, offset, unrelated), unrelated.after(), amount("3")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one or two");
  }

  @Test
  void grantReplayRequiresTheOriginalCreatedAtBeforeAndAfterRehydrate() {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");
    PointRewardWriteResult original =
        grantAt(account, "grant", "entitlement", "2", NOW);

    assertThat(grantAt(account, "grant", "entitlement", "2", NOW).entries().getFirst())
        .isSameAs(original.entries().getFirst());
    assertThatThrownBy(
            () -> grantAt(account, "grant", "entitlement", "2", NOW.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("idempotency");

    PointRewardAccount restored = PointRewardAccount.rehydrate("beneficiary", account.entries());
    assertThatThrownBy(
            () -> grantAt(restored, "grant", "entitlement", "2", NOW.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("idempotency");
  }

  @Test
  void reverseReplayRequiresTheOriginalCreatedAtAndRestoresTheFullDebtCompound() {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");
    grantAt(account, "grant", "entitlement", "10", NOW);
    PointRewardWriteResult original = reverseAt(account, NOW);

    PointRewardWriteResult sameTime = reverseAt(account, NOW);
    assertThat(sameTime.entries().getFirst()).isSameAs(original.entries().getFirst());
    assertThat(sameTime.entries().getLast()).isSameAs(original.entries().getLast());
    assertThatThrownBy(() -> reverseAt(account, NOW.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("idempotency");

    PointRewardAccount restored = PointRewardAccount.rehydrate("beneficiary", account.entries());
    PointRewardWriteResult restoredReplay = reverseAt(restored, NOW);
    assertThat(restoredReplay.entries().getFirst()).isSameAs(original.entries().getFirst());
    assertThat(restoredReplay.entries().getLast()).isSameAs(original.entries().getLast());
    assertThat(restoredReplay.netRewardToCredit()).isEqualTo(Decimal4.ZERO);
    assertThatThrownBy(() -> reverseAt(restored, NOW.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("idempotency");
  }

  @Test
  void oneHundredDifferentSerialGrantsPreserveSequenceTotalsAndRehydrate() {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");

    for (int index = 0; index < 100; index++) {
      grantAt(
          account,
          "event-" + index,
          "entitlement-" + index,
          "1",
          NOW.plusSeconds(index));
    }

    assertThat(account.entries()).hasSize(100);
    assertThat(account.entries())
        .extracting(ReferralPointEntitlement::sequenceNo)
        .containsExactlyElementsOf(
            java.util.stream.LongStream.rangeClosed(1, 100).boxed().toList());
    assertThat(account.currentSnapshot().cumulativeGranted()).isEqualTo(amount("100"));
    PointRewardAccount restored = PointRewardAccount.rehydrate("beneficiary", account.entries());
    assertThat(restored.entries()).containsExactlyElementsOf(account.entries());
    assertThat(restored.currentSnapshot()).isEqualTo(account.currentSnapshot());
  }

  @Test
  void vocabularyIsClosedAndContainsNoWithdrawalOrCashActions() {
    assertThat(PointRewardActionType.values())
        .containsExactlyInAnyOrder(
            PointRewardActionType.POINT_REWARD_GRANTED,
            PointRewardActionType.POINT_REWARD_REVERSED,
            PointRewardActionType.POINT_DEBT_CREATED,
            PointRewardActionType.POINT_DEBT_OFFSET);
    assertThat(PointRewardReason.values())
        .containsExactlyInAnyOrder(
            PointRewardReason.TASK_SUCCESS_REWARD_GRANTED,
            PointRewardReason.TASK_SUCCESS_REWARD_REVERSED,
            PointRewardReason.REWARD_REVERSAL_SHORTFALL,
            PointRewardReason.AUTOMATIC_POINT_DEBT_OFFSET);
  }

  private static PointRewardAccount accountWithDebt() {
    PointRewardAccount account = PointRewardAccount.create("beneficiary");
    grant(account, "grant", "entitlement", "10");
    account.reverse(
        "reverse",
        "entitlement",
        "settlement",
        "allocation-entitlement",
        "funding-lot",
        "consumer",
        8,
        CashTier.NONE,
        amount("8"),
        amount("2"),
        NOW);
    return account;
  }

  private static PointRewardWriteResult grant(
      PointRewardAccount account, String event, String entitlement, String points) {
    return grantAt(account, event, entitlement, points, NOW);
  }

  private static PointRewardWriteResult grantAt(
      PointRewardAccount account,
      String event,
      String entitlement,
      String points,
      Instant createdAt) {
    return account.grant(
        event,
        entitlement,
        "settlement",
        "allocation-" + entitlement,
        "funding-lot",
        "consumer",
        8,
        CashTier.NONE,
        amount(points),
        createdAt);
  }

  private static PointRewardWriteResult reverseAt(
      PointRewardAccount account, Instant createdAt) {
    return account.reverse(
        "reverse",
        "entitlement",
        "settlement",
        "allocation-entitlement",
        "funding-lot",
        "consumer",
        8,
        CashTier.NONE,
        amount("8"),
        amount("2"),
        createdAt);
  }

  private static ReferralPointEntitlement copy(
      ReferralPointEntitlement fact,
      long sequence,
      PointRewardSnapshot before,
      PointRewardSnapshot after,
      String event,
      String entitlement) {
    return new ReferralPointEntitlement(
        sequence,
        fact.beneficiaryUserId(),
        fact.actionType(),
        fact.points4(),
        before,
        after,
        event,
        entitlement,
        fact.sourceSettlementId(),
        "allocation-" + entitlement,
        fact.sourceFundingLotId(),
        fact.sourceWalletOwnerUserId(),
        fact.referralBindingVersion(),
        fact.cashTier(),
        fact.recoveredNow4(),
        fact.createdAt(),
        fact.reason());
  }

  private static Decimal4 amount(String value) {
    return Decimal4.parse(value);
  }
}
