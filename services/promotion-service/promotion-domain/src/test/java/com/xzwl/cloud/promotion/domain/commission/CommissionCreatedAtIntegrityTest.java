package com.xzwl.cloud.promotion.domain.commission;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class CommissionCreatedAtIntegrityTest {

  private static final Instant CREATED_AT = Instant.parse("2026-08-31T01:00:00Z");
  private static final Instant OTHER_TIME = CREATED_AT.plusSeconds(1);

  @ParameterizedTest
  @EnumSource(ReplayAction.class)
  void currentAndRehydratedReplayRequireTheOriginalCreatedAt(ReplayAction action) {
    CommissionAccount original = completedAccount();

    assertThatCode(() -> replay(action, original, CREATED_AT)).doesNotThrowAnyException();
    assertThatThrownBy(() -> replay(action, original, OTHER_TIME))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("idempotency");

    CommissionAccount restored = CommissionAccount.rehydrate("promoter", original.entries());
    assertThatCode(() -> replay(action, restored, CREATED_AT)).doesNotThrowAnyException();
    assertThatThrownBy(() -> replay(action, restored, OTHER_TIME))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("idempotency");
  }

  @ParameterizedTest
  @MethodSource("compoundHistories")
  void rehydrateRejectsFollowUpWithDifferentCreatedAt(List<CommissionEntry> history) {
    List<CommissionEntry> tampered = new ArrayList<>(history);
    CommissionEntry followUp = tampered.getLast();
    tampered.set(tampered.size() - 1, copyAt(followUp, OTHER_TIME));

    assertThatThrownBy(() -> CommissionAccount.rehydrate("promoter", tampered))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("follow-up");
  }

  private static Stream<List<CommissionEntry>> compoundHistories() {
    return Stream.of(
        debtCreatedHistory(), debtOffsetHistory(), withdrawalRejectionOffsetHistory());
  }

  private static List<CommissionEntry> debtCreatedHistory() {
    CommissionAccount account = baseAccount();
    account.requestWithdrawal("request-debt", "withdrawal-debt", amount("8"), CREATED_AT);
    account.reverseRelease("reverse-debt", "allocation", amount("10"), CREATED_AT);
    return account.entries();
  }

  private static List<CommissionEntry> debtOffsetHistory() {
    CommissionAccount account = baseAccount();
    account.requestWithdrawal("request-debt", "withdrawal-debt", amount("8"), CREATED_AT);
    account.reverseRelease("reverse-debt", "allocation", amount("10"), CREATED_AT);
    account.freezeCommission(
        "freeze-offset", "order-offset", "lot-offset", amount("2"), 2, CashTier.LEVEL_1, CREATED_AT);
    account.releaseCommission(
        "release-offset", "order-offset", "lot-offset", "allocation-offset", amount("2"), CREATED_AT);
    return account.entries();
  }

  private static List<CommissionEntry> withdrawalRejectionOffsetHistory() {
    CommissionAccount account = baseAccount();
    account.requestWithdrawal("request-debt", "withdrawal-debt", amount("8"), CREATED_AT);
    account.reverseRelease("reverse-debt", "allocation", amount("10"), CREATED_AT);
    account.rejectWithdrawal(
        "reject-debt",
        "withdrawal-debt",
        CommissionEntryReason.WITHDRAWAL_ACCOUNT_REJECTED,
        CREATED_AT);
    return account.entries();
  }

  private static CommissionAccount completedAccount() {
    CommissionAccount account = CommissionAccount.create("promoter");
    account.freezeCommission(
        "freeze", "order", "lot", amount("30"), 1, CashTier.LEVEL_1, CREATED_AT);
    account.releaseCommission(
        "release", "order", "lot", "allocation", amount("15"), CREATED_AT);
    account.requestWithdrawal("request-reject", "withdrawal-reject", amount("5"), CREATED_AT);
    account.rejectWithdrawal(
        "reject",
        "withdrawal-reject",
        CommissionEntryReason.WITHDRAWAL_ACCOUNT_REJECTED,
        CREATED_AT);
    account.requestWithdrawal("request-paid", "withdrawal-paid", amount("5"), CREATED_AT);
    account.markWithdrawalPaid(
        "paid",
        "withdrawal-paid",
        CommissionEntryReason.WITHDRAWAL_OFFLINE_CONFIRMED_PAID,
        CREATED_AT);
    account.reverseRelease("reverse", "allocation", amount("5"), CREATED_AT);
    account.clawbackUnreleasedRefund("refund", "order", "lot", amount("5"), CREATED_AT);
    return account;
  }

  private static CommissionAccount baseAccount() {
    CommissionAccount account = CommissionAccount.create("promoter");
    account.freezeCommission(
        "freeze", "order", "lot", amount("10"), 1, CashTier.LEVEL_1, CREATED_AT);
    account.releaseCommission(
        "release", "order", "lot", "allocation", amount("10"), CREATED_AT);
    return account;
  }

  private static void replay(
      ReplayAction action, CommissionAccount account, Instant createdAt) {
    switch (action) {
      case FREEZE ->
          account.freezeCommission(
              "freeze", "order", "lot", amount("30"), 1, CashTier.LEVEL_1, createdAt);
      case RELEASE ->
          account.releaseCommission(
              "release", "order", "lot", "allocation", amount("15"), createdAt);
      case REVERSE -> account.reverseRelease("reverse", "allocation", amount("5"), createdAt);
      case REFUND ->
          account.clawbackUnreleasedRefund("refund", "order", "lot", amount("5"), createdAt);
      case WITHDRAWAL_REQUEST ->
          account.requestWithdrawal(
              "request-reject", "withdrawal-reject", amount("5"), createdAt);
      case WITHDRAWAL_REJECT ->
          account.rejectWithdrawal(
              "reject",
              "withdrawal-reject",
              CommissionEntryReason.WITHDRAWAL_ACCOUNT_REJECTED,
              createdAt);
      case WITHDRAWAL_PAID ->
          account.markWithdrawalPaid(
              "paid",
              "withdrawal-paid",
              CommissionEntryReason.WITHDRAWAL_OFFLINE_CONFIRMED_PAID,
              createdAt);
    }
  }

  private static CommissionEntry copyAt(CommissionEntry entry, Instant createdAt) {
    return new CommissionEntry(
        entry.sequenceNo(),
        entry.beneficiaryPromoterUserId(),
        entry.actionType(),
        entry.amount4(),
        entry.before(),
        entry.after(),
        entry.sourceEventId(),
        entry.orderId(),
        entry.fundingLotId(),
        entry.allocationId(),
        entry.withdrawalRequestId(),
        entry.referralBindingVersion(),
        entry.cashTier(),
        createdAt,
        entry.reason());
  }

  private static Decimal4 amount(String value) {
    return Decimal4.parse(value);
  }

  private enum ReplayAction {
    FREEZE,
    RELEASE,
    REVERSE,
    REFUND,
    WITHDRAWAL_REQUEST,
    WITHDRAWAL_REJECT,
    WITHDRAWAL_PAID
  }
}
