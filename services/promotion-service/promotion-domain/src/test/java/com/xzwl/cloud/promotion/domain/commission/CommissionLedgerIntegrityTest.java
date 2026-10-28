package com.xzwl.cloud.promotion.domain.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommissionLedgerIntegrityTest {

  private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");

  @Test
  void rehydrateRejectsCompoundDebtFactWithTamperedSourcePosition() {
    CommissionAccount account = CommissionAccount.create("promoter");
    account.freezeCommission("freeze", "order", "lot", amount("10"), 4, CashTier.LEVEL_2, NOW);
    account.releaseCommission("release", "order", "lot", "allocation", amount("10"), NOW);
    account.requestWithdrawal("withdraw", "request", amount("8"), NOW);
    account.reverseRelease("reverse", "allocation", amount("10"), NOW);
    List<CommissionEntry> history = new ArrayList<>(account.entries());
    CommissionEntry debt = history.getLast();
    history.set(
        history.size() - 1,
        new CommissionEntry(
            debt.sequenceNo(),
            debt.beneficiaryPromoterUserId(),
            debt.actionType(),
            debt.amount4(),
            debt.before(),
            debt.after(),
            debt.sourceEventId(),
            "tampered-order",
            debt.fundingLotId(),
            debt.allocationId(),
            debt.withdrawalRequestId(),
            debt.referralBindingVersion(),
            debt.cashTier(),
            debt.createdAt(),
            debt.reason()));

    assertThatThrownBy(() -> CommissionAccount.rehydrate("promoter", history))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("follow-up");
  }

  @Test
  void rehydrateRejectsDuplicateLotDuplicateKeyAndMissingAutomaticOffset() {
    CommissionAccount account = CommissionAccount.create("promoter");
    CommissionEntry frozen =
        account.freezeCommission("freeze", "order", "lot", amount("5"), 1, CashTier.LEVEL_1, NOW);
    CommissionEntry duplicateLot =
        new CommissionEntry(
            2,
            "promoter",
            CommissionActionType.COMMISSION_FROZEN,
            amount("1"),
            frozen.after(),
            frozen.after().frozenBy(amount("1")),
            "freeze-other-event",
            "order",
            "lot",
            null,
            null,
            1L,
            CashTier.LEVEL_1,
            NOW,
            CommissionEntryReason.RECHARGE_COMMISSION_FROZEN);
    assertThatThrownBy(() -> CommissionAccount.rehydrate("promoter", List.of(frozen, duplicateLot)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("funding lot");

    CommissionEntry duplicateKey =
        new CommissionEntry(
            2,
            "promoter",
            CommissionActionType.COMMISSION_FROZEN,
            amount("1"),
            frozen.after(),
            frozen.after().frozenBy(amount("1")),
            "freeze",
            "order-2",
            "lot-2",
            null,
            null,
            1L,
            CashTier.LEVEL_1,
            NOW,
            CommissionEntryReason.RECHARGE_COMMISSION_FROZEN);
    assertThatThrownBy(() -> CommissionAccount.rehydrate("promoter", List.of(frozen, duplicateKey)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("idempotency");

    CommissionAccount withDebt = CommissionAccount.create("promoter");
    withDebt.freezeCommission("freeze-a", "order-a", "lot-a", amount("10"), 1, CashTier.LEVEL_1, NOW);
    withDebt.releaseCommission("release-a", "order-a", "lot-a", "allocation-a", amount("10"), NOW);
    withDebt.requestWithdrawal("withdraw-a", "request-a", amount("10"), NOW);
    withDebt.reverseRelease("reverse-a", "allocation-a", amount("10"), NOW);
    withDebt.freezeCommission("freeze-b", "order-b", "lot-b", amount("2"), 2, CashTier.LEVEL_1, NOW);
    withDebt.releaseCommission("release-b", "order-b", "lot-b", "allocation-b", amount("2"), NOW);
    List<CommissionEntry> missingOffset = new ArrayList<>(withDebt.entries());
    assertThat(missingOffset.removeLast().actionType()).isEqualTo(CommissionActionType.CASH_DEBT_OFFSET);
    assertThatThrownBy(() -> CommissionAccount.rehydrate("promoter", missingOffset))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("follow-up");
  }

  @Test
  void actionVocabularyContainsNoCredentialUploadFact() {
    assertThat(CommissionActionType.values())
        .containsExactlyInAnyOrder(
            CommissionActionType.COMMISSION_FROZEN,
            CommissionActionType.COMMISSION_RELEASED,
            CommissionActionType.COMMISSION_RELEASE_REVERSED,
            CommissionActionType.RECHARGE_REFUND_CLAWBACK,
            CommissionActionType.CASH_DEBT_CREATED,
            CommissionActionType.CASH_DEBT_OFFSET,
            CommissionActionType.WITHDRAWAL_REJECTION_DEBT_OFFSET,
            CommissionActionType.WITHDRAWAL_REQUESTED,
            CommissionActionType.WITHDRAWAL_REJECTED,
            CommissionActionType.WITHDRAWAL_PAID);
  }

  @Test
  void rehydratesWithdrawalRejectionDebtOffsetAndRejectsMissingOrTamperedFollowUp() {
    CommissionAccount account = CommissionAccount.create("promoter");
    account.freezeCommission("freeze", "order", "lot", amount("10"), 1, CashTier.LEVEL_1, NOW);
    account.releaseCommission("release", "order", "lot", "allocation", amount("10"), NOW);
    account.requestWithdrawal("request", "withdrawal", amount("8"), NOW);
    account.reverseRelease("reverse", "allocation", amount("10"), NOW);
    CommissionWriteResult rejected =
        account.rejectWithdrawal(
            "reject",
            "withdrawal",
            CommissionEntryReason.WITHDRAWAL_ACCOUNT_REJECTED,
            NOW);

    CommissionAccount restored = CommissionAccount.rehydrate("promoter", account.entries());
    CommissionWriteResult replay =
        restored.rejectWithdrawal(
            "reject",
            "withdrawal",
            CommissionEntryReason.WITHDRAWAL_ACCOUNT_REJECTED,
            NOW);
    assertThat(restored.currentSnapshot()).isEqualTo(account.currentSnapshot());
    assertThat(replay.entries().getFirst()).isSameAs(rejected.entries().getFirst());
    assertThat(replay.entries().getLast()).isSameAs(rejected.entries().getLast());

    List<CommissionEntry> missing = new ArrayList<>(account.entries());
    CommissionEntry offset = missing.removeLast();
    assertThatThrownBy(() -> CommissionAccount.rehydrate("promoter", missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("follow-up");

    assertThatThrownBy(
            () ->
                new CommissionEntry(
                    offset.sequenceNo(),
                    offset.beneficiaryPromoterUserId(),
                    offset.actionType(),
                    offset.amount4(),
                    offset.before(),
                    offset.after(),
                    offset.sourceEventId(),
                    offset.orderId(),
                    offset.fundingLotId(),
                    offset.allocationId(),
                    offset.withdrawalRequestId(),
                    offset.referralBindingVersion(),
                    offset.cashTier(),
                    offset.createdAt(),
                    CommissionEntryReason.AUTOMATIC_CASH_DEBT_OFFSET))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");

    List<CommissionEntry> tampered = new ArrayList<>(account.entries());
    tampered.set(
        tampered.size() - 1,
        new CommissionEntry(
            offset.sequenceNo(),
            offset.beneficiaryPromoterUserId(),
            offset.actionType(),
            offset.amount4(),
            offset.before(),
            offset.after(),
            offset.sourceEventId(),
            offset.orderId(),
            offset.fundingLotId(),
            offset.allocationId(),
            "other-withdrawal",
            offset.referralBindingVersion(),
            offset.cashTier(),
            offset.createdAt(),
            offset.reason()));
    assertThatThrownBy(() -> CommissionAccount.rehydrate("promoter", tampered))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("follow-up");
  }

  private static Decimal4 amount(String value) {
    return Decimal4.parse(value);
  }
}
