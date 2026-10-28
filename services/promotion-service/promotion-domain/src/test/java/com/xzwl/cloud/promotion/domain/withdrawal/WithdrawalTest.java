package com.xzwl.cloud.promotion.domain.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.commission.CommissionBalanceSnapshot;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.payout.BankCardDetails;
import com.xzwl.cloud.promotion.domain.payout.PayoutAccount;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WithdrawalTest {

  private static final Instant T0 = Instant.parse("2026-09-04T00:00:00Z");

  @Test
  void requestValidatesCashRulesAndSavesAnImmutableActiveOwnedPayoutSnapshot() {
    PayoutAccount payout = payoutAccount("promoter-1");
    Withdrawal withdrawal =
        request(payout, AccountStatus.ENABLED, balances("150", "0"), "100.00", "100.00");

    assertThat(withdrawal.status()).isEqualTo(WithdrawalStatus.WITHDRAWING);
    assertThat(withdrawal.amount()).isEqualTo(amount("100"));
    assertThat(withdrawal.requestCommandId()).isEqualTo("request-command-1");
    assertThat(withdrawal.payoutSnapshot().accountId()).isEqualTo("payout-1");
    assertThat(withdrawal.payoutSnapshot().promoterId()).isEqualTo("promoter-1");

    payout.update(
        new BankCardDetails("李四", "6222020000004321", "新银行"), T0.plusSeconds(1));
    assertThat(((BankCardDetails) withdrawal.payoutSnapshot().details()).cardNumber())
        .isEqualTo("6222021234567890");
  }

  @Test
  void requestRejectsInvalidPrecisionMinimumAvailabilityDebtPromoterAndPayoutAccount() {
    PayoutAccount payout = payoutAccount("promoter-1");

    assertThatThrownBy(
            () -> request(payout, AccountStatus.ENABLED, balances("150", "0"), "100.001", "100"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("two decimal");
    assertThatThrownBy(
            () -> request(payout, AccountStatus.ENABLED, balances("150", "0"), "99.99", "100"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("minimum");
    assertThatThrownBy(
            () -> request(payout, AccountStatus.ENABLED, balances("99", "0"), "100", "100"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("withdrawable");
    assertThatThrownBy(
            () -> request(payout, AccountStatus.ENABLED, balances("150", "1"), "100", "100"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cash debt");
    assertThatThrownBy(
            () -> request(payout, AccountStatus.DISABLED, balances("150", "0"), "100", "100"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("disabled");
    assertThatThrownBy(
            () -> request(payoutAccount("other-promoter"), AccountStatus.ENABLED,
                balances("150", "0"), "100", "100"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owned");

    payout.disable(T0.plusSeconds(1));
    assertThatThrownBy(
            () -> request(payout, AccountStatus.ENABLED, balances("150", "0"), "100", "100"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active");
  }

  @Test
  void pointDebtIsNotAWithdrawalInputAndDoesNotAffectCashEligibility() {
    Withdrawal withdrawal =
        request(payoutAccount("promoter-1"), AccountStatus.ENABLED,
            balances("100", "0"), "100", "100");

    assertThat(withdrawal.status()).isEqualTo(WithdrawalStatus.WITHDRAWING);
    assertThat(
            Arrays.stream(Withdrawal.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("request"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
        .allMatch(type -> !type.getName().toLowerCase().contains("point"));
  }

  @Test
  void requestReplayUsesTheStoredCommandAmountAndPayoutIdentity() {
    Withdrawal withdrawal = validWithdrawal("withdrawal-request-replay");

    assertThat(
            withdrawal.replayRequest(
                "request-withdrawal-request-replay", "100.0", "payout-1"))
        .isSameAs(withdrawal);
    assertThatThrownBy(
            () ->
                withdrawal.replayRequest(
                    "request-withdrawal-request-replay", "99.00", "payout-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("idempotency");
    assertThatThrownBy(
            () -> withdrawal.replayRequest("another-command", "100.00", "payout-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("idempotency");
  }

  @Test
  void normalLifecycleOnlyApprovesThenPaysOrRejectsWhileWithdrawing() {
    Withdrawal approved = validWithdrawal("withdrawal-approved");
    WithdrawalTransitionResult approval =
        approved.approve("approve-1", "admin-1", T0.plusSeconds(1));
    assertThat(approval.status()).isEqualTo(WithdrawalStatus.PENDING_PAYOUT);
    assertThat(approval.balanceAction()).isEmpty();

    WithdrawalTransitionResult paid =
        approved.markPaid("paid-1", "admin-1", T0.plusSeconds(2));
    assertThat(paid.status()).isEqualTo(WithdrawalStatus.PAID);
    assertThat(paid.balanceAction()).get()
        .extracting(WithdrawalBalanceAction::type)
        .isEqualTo(WithdrawalBalanceActionType.CONFIRM_PAID);
    assertThatThrownBy(
            () -> approved.reject("reject-after-paid", "admin-1", "too late", T0.plusSeconds(3)))
        .isInstanceOf(IllegalStateException.class);

    Withdrawal rejected = validWithdrawal("withdrawal-rejected");
    WithdrawalTransitionResult rejection =
        rejected.reject("reject-1", "admin-1", "资料不符", T0.plusSeconds(1));
    assertThat(rejection.status()).isEqualTo(WithdrawalStatus.REJECTED);
    assertThat(rejection.balanceAction()).get()
        .extracting(WithdrawalBalanceAction::type)
        .isEqualTo(WithdrawalBalanceActionType.RELEASE_RESERVATION);
    assertThat(rejected.rejectionType()).contains(WithdrawalRejectionType.ADMIN_REJECTED);
    assertThat(rejected.rejectionReason()).contains("资料不符");
    assertThatThrownBy(
            () -> rejected.markPaid("paid-after-reject", "admin-1", T0.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void ordinaryRejectCannotRejectPendingPayoutButChargebackCompensationCan() {
    Withdrawal withdrawal = validWithdrawal("withdrawal-chargeback");
    withdrawal.approve("approve-1", "admin-1", T0.plusSeconds(1));

    assertThatThrownBy(
            () -> withdrawal.reject("reject-1", "admin-1", "普通拒绝", T0.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);

    WithdrawalTransitionResult compensated =
        withdrawal.compensateChargeback("chargeback-1", "order-1", T0.plusSeconds(2));
    assertThat(compensated.status()).isEqualTo(WithdrawalStatus.REJECTED);
    assertThat(compensated.balanceAction()).get()
        .satisfies(
            action -> {
              assertThat(action.type()).isEqualTo(WithdrawalBalanceActionType.CHARGEBACK_CLAWBACK);
              assertThat(action.reason()).isEqualTo(WithdrawalBalanceActionReason.ORIGINAL_ORDER_CHARGEBACK);
            });
    assertThat(withdrawal.rejectionType())
        .contains(WithdrawalRejectionType.CHARGEBACK_COMPENSATION);
    assertThat(withdrawal.rejectionReason()).get().asString().contains("order-1");
  }

  @Test
  void chargebackCompensationClawsBackWithdrawingWithoutReleasingItToAvailable() {
    Withdrawal withdrawal = validWithdrawal("withdrawal-withdrawing-chargeback");

    WithdrawalTransitionResult compensated =
        withdrawal.compensateChargeback("chargeback-1", "order-1", T0.plusSeconds(1));

    assertThat(compensated.status()).isEqualTo(WithdrawalStatus.REJECTED);
    assertThat(compensated.balanceAction()).get()
        .satisfies(
            action -> {
              assertThat(action.type()).isEqualTo(WithdrawalBalanceActionType.CHARGEBACK_CLAWBACK);
              assertThat(action.reason()).isEqualTo(WithdrawalBalanceActionReason.ORIGINAL_ORDER_CHARGEBACK);
              assertThat(action.amount()).isEqualTo(amount("100"));
              assertThat(action.type()).isNotEqualTo(WithdrawalBalanceActionType.RELEASE_RESERVATION);
            });
    assertThat(withdrawal.rejectionType())
        .contains(WithdrawalRejectionType.CHARGEBACK_COMPENSATION);
  }

  @Test
  void sameCommandAndSemanticsReplayOriginalResultButConflictsAreRejected() {
    Withdrawal withdrawal = validWithdrawal("withdrawal-idempotent");
    WithdrawalTransitionResult original =
        withdrawal.approve("command-1", "admin-1", T0.plusSeconds(1));

    assertThat(withdrawal.approve("command-1", "admin-1", T0.plusSeconds(1)))
        .isSameAs(original);
    assertThatThrownBy(
            () -> withdrawal.approve("command-1", "admin-2", T0.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("idempotency");
    assertThatThrownBy(
            () -> withdrawal.approve("new-command", "admin-1", T0.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void terminalStatesAndBlankRejectReasonAreRejected() {
    Withdrawal rejected = validWithdrawal("withdrawal-terminal");
    assertThatThrownBy(
            () -> rejected.reject("reject-blank", "admin-1", "  ", T0.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reason");
    rejected.reject("reject-1", "admin-1", "资料不符", T0.plusSeconds(1));
    assertThatThrownBy(
            () -> rejected.compensateChargeback("chargeback-1", "order-1", T0.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);
  }

  private static Withdrawal request(
      PayoutAccount payout,
      AccountStatus accountStatus,
      CommissionBalanceSnapshot balances,
      String requested,
      String minimum) {
    return Withdrawal.request(
        "withdrawal-1",
        "promoter-1",
        accountStatus,
        payout,
        balances,
        requested,
        amount(minimum),
        "request-command-1",
        T0);
  }

  private static Withdrawal validWithdrawal(String id) {
    return Withdrawal.request(
        id,
        "promoter-1",
        AccountStatus.ENABLED,
        payoutAccount("promoter-1"),
        balances("150", "0"),
        "100.00",
        amount("100"),
        "request-" + id,
        T0);
  }

  private static PayoutAccount payoutAccount(String promoterId) {
    return PayoutAccount.create(
        "payout-1",
        promoterId,
        com.xzwl.cloud.promotion.domain.payout.PayoutAccountType.BANK_CARD,
        new BankCardDetails("张三", "6222021234567890", "测试银行"),
        T0);
  }

  private static CommissionBalanceSnapshot balances(String available, String cashDebt) {
    return new CommissionBalanceSnapshot(
        Decimal4.ZERO, amount(available), Decimal4.ZERO, Decimal4.ZERO, amount(cashDebt));
  }

  private static Decimal4 amount(String value) {
    return Decimal4.of(value);
  }
}
