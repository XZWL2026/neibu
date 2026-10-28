package com.xzwl.cloud.promotion.domain.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommissionAccountTest {

  private static final Instant T0 = Instant.parse("2026-08-30T00:00:00Z");

  @Test
  void freezesLevelOneAndLevelTwoButRejectsNone() {
    CommissionAccount levelOne = CommissionAccount.create("promoter-b");
    CommissionEntry bEntry =
        levelOne.freezeCommission(
            "recharge-b", "order-a", "lot-b", amount("10"), 7, CashTier.LEVEL_1, T0);

    assertThat(bEntry.sequenceNo()).isEqualTo(1);
    assertThat(bEntry.actionType()).isEqualTo(CommissionActionType.COMMISSION_FROZEN);
    assertThat(bEntry.before()).isEqualTo(CommissionBalanceSnapshot.zero());
    assertThat(bEntry.after().frozen()).isEqualTo(amount("10"));
    assertThat(bEntry.orderId()).isEqualTo("order-a");
    assertThat(bEntry.fundingLotId()).isEqualTo("lot-b");
    assertThat(bEntry.referralBindingVersion()).isEqualTo(7L);
    assertThat(bEntry.cashTier()).isEqualTo(CashTier.LEVEL_1);

    CommissionAccount levelTwo = CommissionAccount.create("promoter-c");
    levelTwo.freezeCommission(
        "recharge-c", "order-a", "lot-c", amount("8"), 7, CashTier.LEVEL_2, T0);
    assertThat(levelTwo.currentSnapshot().frozen()).isEqualTo(amount("8"));

    CommissionAccount none = CommissionAccount.create("promoter-d");
    assertThatThrownBy(
            () ->
                none.freezeCommission(
                    "recharge-d", "order-a", "lot-d", amount("8"), 7, CashTier.NONE, T0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cash tier");
    assertThat(none.entries()).isEmpty();
  }

  @Test
  void releasesFrozenCommissionInExactAllocationAmounts() {
    CommissionAccount account = frozenAccount("20");

    CommissionWriteResult first =
        account.releaseCommission(
            "task-1", "order-a", "lot-a", "allocation-1", amount("7.25"), T0.plusSeconds(1));
    CommissionWriteResult second =
        account.releaseCommission(
            "task-2", "order-a", "lot-a", "allocation-2", amount("12.75"), T0.plusSeconds(2));

    assertThat(first.entries()).hasSize(1);
    assertThat(first.entries().getFirst().actionType())
        .isEqualTo(CommissionActionType.COMMISSION_RELEASED);
    assertThat(first.snapshot().frozen()).isEqualTo(amount("12.75"));
    assertThat(first.snapshot().available()).isEqualTo(amount("7.25"));
    assertThat(second.snapshot().frozen()).isEqualTo(Decimal4.ZERO);
    assertThat(second.snapshot().available()).isEqualTo(amount("20"));
    assertThat(account.entries()).extracting(CommissionEntry::sequenceNo).containsExactly(1L, 2L, 3L);

    assertThatThrownBy(
            () ->
                account.releaseCommission(
                    "task-3",
                    "order-a",
                    "lot-a",
                    "allocation-3",
                    amount("0.0001"),
                    T0.plusSeconds(3)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("frozen");
  }

  @Test
  void reverseUsesAvailableFirstThenCreatesDebtAndFutureReleaseOffsetsIt() {
    CommissionAccount account = frozenAccount("20");
    account.releaseCommission(
        "task-1", "order-a", "lot-a", "allocation-1", amount("8"), T0.plusSeconds(1));
    account.requestWithdrawal("withdraw-evt", "withdrawal-1", amount("5"), T0.plusSeconds(2));

    CommissionWriteResult reversed =
        account.reverseRelease("reverse-evt", "allocation-1", amount("7"), T0.plusSeconds(3));

    assertThat(reversed.entries())
        .extracting(CommissionEntry::actionType)
        .containsExactly(
            CommissionActionType.COMMISSION_RELEASE_REVERSED,
            CommissionActionType.CASH_DEBT_CREATED);
    assertThat(reversed.snapshot().available()).isEqualTo(Decimal4.ZERO);
    assertThat(reversed.snapshot().withdrawing()).isEqualTo(amount("5"));
    assertThat(reversed.snapshot().cashDebt()).isEqualTo(amount("4"));

    CommissionWriteResult released =
        account.releaseCommission(
            "task-2", "order-a", "lot-a", "allocation-2", amount("6"), T0.plusSeconds(4));

    assertThat(released.entries())
        .extracting(CommissionEntry::actionType)
        .containsExactly(
            CommissionActionType.COMMISSION_RELEASED, CommissionActionType.CASH_DEBT_OFFSET);
    assertThat(released.snapshot().cashDebt()).isEqualTo(Decimal4.ZERO);
    assertThat(released.snapshot().available()).isEqualTo(amount("2"));
  }

  @Test
  void refundClawbackOnlyUsesTheReferencedLotsUnreleasedFrozenAmount() {
    CommissionAccount account = CommissionAccount.create("promoter-b");
    account.freezeCommission(
        "freeze-1", "order-1", "lot-1", amount("10"), 2, CashTier.LEVEL_1, T0);
    account.freezeCommission(
        "freeze-2", "order-2", "lot-2", amount("10"), 3, CashTier.LEVEL_1, T0);
    account.releaseCommission(
        "release-1", "order-1", "lot-1", "allocation-1", amount("6"), T0);

    CommissionEntry clawback =
        account.clawbackUnreleasedRefund(
            "refund-1", "order-1", "lot-1", amount("4"), T0.plusSeconds(1));

    assertThat(clawback.actionType()).isEqualTo(CommissionActionType.RECHARGE_REFUND_CLAWBACK);
    assertThat(account.currentSnapshot().frozen()).isEqualTo(amount("10"));
    assertThatThrownBy(
            () ->
                account.clawbackUnreleasedRefund(
                    "refund-2", "order-1", "lot-1", amount("1"), T0.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("release reversal");
    assertThat(account.currentSnapshot().frozen()).isEqualTo(amount("10"));
  }

  @Test
  void withdrawalMovesAvailableThroughRejectedAndPaidStatesAndDebtBlocksRequests() {
    CommissionAccount account = frozenAccount("15");
    account.releaseCommission(
        "release-1", "order-a", "lot-a", "allocation-1", amount("15"), T0);

    CommissionEntry requested =
        account.requestWithdrawal("request-1", "withdrawal-1", amount("6"), T0.plusSeconds(1));
    assertThat(requested.after().available()).isEqualTo(amount("9"));
    assertThat(requested.after().withdrawing()).isEqualTo(amount("6"));

    CommissionWriteResult rejected =
        account.rejectWithdrawal(
            "reject-1",
            "withdrawal-1",
            CommissionEntryReason.WITHDRAWAL_COMPLIANCE_REJECTED,
            T0.plusSeconds(2));
    assertThat(rejected.snapshot().available()).isEqualTo(amount("15"));
    assertThat(rejected.snapshot().withdrawing()).isEqualTo(Decimal4.ZERO);

    account.requestWithdrawal("request-2", "withdrawal-2", amount("10"), T0.plusSeconds(3));
    CommissionEntry paid =
        account.markWithdrawalPaid(
            "paid-2",
            "withdrawal-2",
            CommissionEntryReason.WITHDRAWAL_OFFLINE_CONFIRMED_PAID,
            T0.plusSeconds(4));
    assertThat(paid.after().withdrawing()).isEqualTo(Decimal4.ZERO);
    assertThat(paid.after().withdrawn()).isEqualTo(amount("10"));

    CommissionAccount debtAccount = frozenAccount("5");
    debtAccount.releaseCommission(
        "release-d", "order-a", "lot-a", "allocation-d", amount("5"), T0);
    debtAccount.requestWithdrawal("withdraw-d", "withdrawal-d", amount("5"), T0);
    debtAccount.reverseRelease("reverse-d", "allocation-d", amount("5"), T0);
    assertThat(debtAccount.currentSnapshot().cashDebt()).isEqualTo(amount("5"));
    assertThatThrownBy(
            () -> debtAccount.requestWithdrawal("blocked", "withdrawal-x", amount("1"), T0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("debt");
  }

  @Test
  void withdrawalTransitionsAreExactlyIdempotentAndRejectWrongStates() {
    CommissionAccount account = frozenAccount("20");
    account.releaseCommission(
        "release", "order-a", "lot-a", "allocation", amount("20"), T0);

    CommissionEntry request =
        account.requestWithdrawal("request-event", "request-1", amount("5"), T0);
    assertThat(
            account.requestWithdrawal(
                "request-event", "request-1", amount("5"), T0))
        .isSameAs(request);
    assertThatThrownBy(
            () ->
                account.requestWithdrawal(
                    "request-event", "request-1", amount("4"), T0.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("idempotency");

    CommissionWriteResult rejected =
        account.rejectWithdrawal(
            "reject-event",
            "request-1",
            CommissionEntryReason.WITHDRAWAL_ACCOUNT_REJECTED,
            T0);
    CommissionWriteResult rejectedReplay =
        account.rejectWithdrawal(
                "reject-event",
                "request-1",
                CommissionEntryReason.WITHDRAWAL_ACCOUNT_REJECTED,
                T0);
    assertThat(rejectedReplay.entries().getFirst()).isSameAs(rejected.entries().getFirst());
    assertThatThrownBy(
            () ->
                account.markWithdrawalPaid(
                    "paid-after-reject",
                    "request-1",
                    CommissionEntryReason.WITHDRAWAL_OFFLINE_CONFIRMED_PAID,
                    T0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("awaiting");

    account.requestWithdrawal("request-event-2", "request-2", amount("6"), T0);
    CommissionEntry paid =
        account.markWithdrawalPaid(
            "paid-event",
            "request-2",
            CommissionEntryReason.WITHDRAWAL_OFFLINE_CONFIRMED_PAID,
            T0);
    assertThat(
            account.markWithdrawalPaid(
                "paid-event",
                "request-2",
                CommissionEntryReason.WITHDRAWAL_OFFLINE_CONFIRMED_PAID,
                T0))
        .isSameAs(paid);
    assertThatThrownBy(
            () ->
                account.rejectWithdrawal(
                    "reject-after-paid",
                    "request-2",
                    CommissionEntryReason.WITHDRAWAL_COMPLIANCE_REJECTED,
                    T0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("awaiting");
  }

  @Test
  void rejectedWithdrawalOffsetsDebtCreatedWhileItWasWithdrawing() {
    CommissionAccount account = frozenAccount("10");
    account.releaseCommission(
        "release", "order-a", "lot-a", "allocation", amount("10"), T0);
    account.requestWithdrawal("request", "withdrawal", amount("8"), T0);
    account.reverseRelease("reverse", "allocation", amount("10"), T0);

    CommissionWriteResult rejected =
        account.rejectWithdrawal(
            "reject",
            "withdrawal",
            CommissionEntryReason.WITHDRAWAL_COMPLIANCE_REJECTED,
            T0);

    assertThat(rejected.entries())
        .extracting(CommissionEntry::actionType)
        .containsExactly(
            CommissionActionType.WITHDRAWAL_REJECTED,
            CommissionActionType.WITHDRAWAL_REJECTION_DEBT_OFFSET);
    assertThat(rejected.entries().getFirst().after().available()).isEqualTo(amount("8"));
    assertThat(rejected.entries().getFirst().after().cashDebt()).isEqualTo(amount("8"));
    assertThat(rejected.entries().getFirst().after().withdrawable()).isEqualTo(Decimal4.ZERO);
    assertThat(rejected.snapshot().available()).isEqualTo(Decimal4.ZERO);
    assertThat(rejected.snapshot().cashDebt()).isEqualTo(Decimal4.ZERO);
  }

  @Test
  void releaseAndWithdrawalRejectionCanShareSourceEventWithoutOffsetKeyCollision() {
    CommissionAccount account = frozenAccount("10");
    account.releaseCommission(
        "release-a", "order-a", "lot-a", "allocation-a", amount("10"), T0);
    account.requestWithdrawal("request-a", "withdrawal-a", amount("10"), T0);
    account.reverseRelease("reverse-a", "allocation-a", amount("10"), T0);
    account.freezeCommission(
        "freeze-b", "order-b", "lot-b", amount("5"), 2, CashTier.LEVEL_1, T0);
    account.releaseCommission(
        "shared-source", "order-b", "lot-b", "allocation-b", amount("5"), T0);

    CommissionWriteResult rejected =
        account.rejectWithdrawal(
            "shared-source",
            "withdrawal-a",
            CommissionEntryReason.WITHDRAWAL_ACCOUNT_REJECTED,
            T0);

    assertThat(rejected.entries())
        .extracting(CommissionEntry::actionType)
        .containsExactly(
            CommissionActionType.WITHDRAWAL_REJECTED,
            CommissionActionType.WITHDRAWAL_REJECTION_DEBT_OFFSET);
    assertThat(CommissionAccount.rehydrate("promoter-b", account.entries()).currentSnapshot())
        .isEqualTo(account.currentSnapshot());
  }

  @Test
  void exactReplayReturnsOriginalFactsAndConflictingReplayIsRejected() {
    CommissionAccount account = frozenAccount("10");

    CommissionWriteResult original =
        account.releaseCommission(
            "task-1", "order-a", "lot-a", "allocation-1", amount("6"), T0.plusSeconds(1));
    CommissionWriteResult replay =
        account.releaseCommission(
            "task-1", "order-a", "lot-a", "allocation-1", amount("6"), T0.plusSeconds(1));

    assertThat(replay.entries().getFirst()).isSameAs(original.entries().getFirst());
    assertThat(account.entries()).hasSize(2);
    assertThatThrownBy(
            () ->
                account.releaseCommission(
                    "task-1",
                    "order-a",
                    "lot-a",
                    "allocation-other",
                    amount("6"),
                    T0.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("idempotency");
    assertThat(account.entries()).hasSize(2);
  }

  @Test
  void compoundReplayReturnsTheOriginalReleaseOffsetAndReversalDebtFacts() {
    CommissionAccount account = frozenAccount("10");
    account.releaseCommission(
        "release-a", "order-a", "lot-a", "allocation-a", amount("10"), T0);
    account.requestWithdrawal("withdraw-a", "withdrawal-a", amount("10"), T0);
    CommissionWriteResult reversal =
        account.reverseRelease("reverse-a", "allocation-a", amount("10"), T0);
    account.freezeCommission(
        "freeze-b", "order-b", "lot-b", amount("4"), 2, CashTier.LEVEL_1, T0);
    CommissionWriteResult release =
        account.releaseCommission(
            "release-b", "order-b", "lot-b", "allocation-b", amount("4"), T0);

    CommissionWriteResult reversalReplay =
        account.reverseRelease("reverse-a", "allocation-a", amount("10"), T0);
    CommissionWriteResult releaseReplay =
        account.releaseCommission(
            "release-b", "order-b", "lot-b", "allocation-b", amount("4"), T0);

    assertThat(reversalReplay.entries()).containsExactlyElementsOf(reversal.entries());
    assertThat(reversalReplay.entries().getFirst()).isSameAs(reversal.entries().getFirst());
    assertThat(reversalReplay.entries().getLast()).isSameAs(reversal.entries().getLast());
    assertThat(releaseReplay.entries()).containsExactlyElementsOf(release.entries());
    assertThat(releaseReplay.entries().getFirst()).isSameAs(release.entries().getFirst());
    assertThat(releaseReplay.entries().getLast()).isSameAs(release.entries().getLast());
    assertThat(account.entries()).hasSize(8);
  }

  @Test
  void rehydratesAllPositionsAndRejectsBrokenHistory() {
    CommissionAccount original = frozenAccount("12");
    original.releaseCommission(
        "release-1", "order-a", "lot-a", "allocation-1", amount("7"), T0);
    original.requestWithdrawal("request-1", "withdrawal-1", amount("2"), T0);
    original.markWithdrawalPaid(
        "paid-1",
        "withdrawal-1",
        CommissionEntryReason.WITHDRAWAL_OFFLINE_CONFIRMED_PAID,
        T0);

    CommissionAccount restored = CommissionAccount.rehydrate("promoter-b", original.entries());

    assertThat(restored.currentSnapshot()).isEqualTo(original.currentSnapshot());
    assertThat(restored.entries()).containsExactlyElementsOf(original.entries());
    assertThat(restored.releaseCommission(
            "release-1", "order-a", "lot-a", "allocation-1", amount("7"), T0).entries())
        .containsExactly(original.entries().get(1));

    List<CommissionEntry> broken =
        List.of(
            original.entries().getFirst(),
            original.entries().get(2),
            original.entries().get(3));
    assertThatThrownBy(() -> CommissionAccount.rehydrate("promoter-b", broken))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence");
    assertThatThrownBy(() -> CommissionAccount.rehydrate("somebody-else", original.entries()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("beneficiary");
  }

  @Test
  void snapshotsAndEntriesValidateAmountsTransitionsAndRedactIdentifiers() {
    assertThatThrownBy(
            () ->
                new CommissionBalanceSnapshot(
                    amount("-0.0001"),
                    Decimal4.ZERO,
                    Decimal4.ZERO,
                    Decimal4.ZERO,
                    Decimal4.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CommissionBalanceSnapshot(
                    null, Decimal4.ZERO, Decimal4.ZERO, Decimal4.ZERO, Decimal4.ZERO))
        .isInstanceOf(NullPointerException.class);

    CommissionEntry entry = frozenAccount("1").entries().getFirst();
    assertThat(entry.toString())
        .doesNotContain("promoter-b", "freeze-a", "order-a", "lot-a")
        .contains("COMMISSION_FROZEN", "1.0000");
    assertThat(entry.before().toString()).doesNotContain("promoter", "order", "event");
    assertThatThrownBy(
            () ->
                new CommissionEntry(
                    1,
                    "promoter-b",
                    CommissionActionType.COMMISSION_FROZEN,
                    amount("1"),
                    CommissionBalanceSnapshot.zero(),
                    CommissionBalanceSnapshot.zero(),
                    "event-x",
                    "order-x",
                    "lot-x",
                    null,
                    null,
                    1L,
                    CashTier.LEVEL_1,
                    T0,
                    CommissionEntryReason.RECHARGE_COMMISSION_FROZEN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("transition");
  }

  @Test
  void rejectsBlankIdentifiersZeroNegativeAndTruncatedToZeroAmounts() {
    CommissionAccount account = CommissionAccount.create("promoter-b");

    assertThatThrownBy(() -> CommissionAccount.create(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                account.freezeCommission(
                    " ", "order", "lot", amount("1"), 1, CashTier.LEVEL_1, T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                account.freezeCommission(
                    "event", "order", "lot", Decimal4.ZERO, 1, CashTier.LEVEL_1, T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                account.freezeCommission(
                    "event", "order", "lot", amount("-1"), 1, CashTier.LEVEL_1, T0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                account.freezeCommission(
                    "event", "order", "lot", amount("0.00009"), 1, CashTier.LEVEL_1, T0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exposesImmutableFactsAndFailsOverflowWithoutPollutingTheLedger() {
    CommissionAccount account = CommissionAccount.create("promoter-b");
    Decimal4 maximum = Decimal4.parse("999999999999999.9999");
    account.freezeCommission(
        "maximum", "order-max", "lot-max", maximum, 1, CashTier.LEVEL_1, T0);

    List<CommissionEntry> facts = account.entries();
    assertThatThrownBy(facts::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                account.freezeCommission(
                    "overflow",
                    "order-overflow",
                    "lot-overflow",
                    amount("0.0001"),
                    2,
                    CashTier.LEVEL_1,
                    T0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("range");
    assertThat(account.entries()).hasSize(1);
    assertThat(account.currentSnapshot().frozen()).isEqualTo(maximum);
  }

  private static CommissionAccount frozenAccount(String value) {
    CommissionAccount account = CommissionAccount.create("promoter-b");
    account.freezeCommission(
        "freeze-a", "order-a", "lot-a", amount(value), 1, CashTier.LEVEL_1, T0);
    return account;
  }

  private static Decimal4 amount(String value) {
    return Decimal4.parse(value);
  }
}
