package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.commission.CommissionBalanceSnapshot;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.payout.BankCardDetails;
import com.xzwl.cloud.promotion.domain.payout.PayoutAccount;
import com.xzwl.cloud.promotion.domain.payout.PayoutAccountType;
import com.xzwl.cloud.promotion.domain.withdrawal.Withdrawal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RequestWithdrawalHandlerTest {

  private static final Instant T0 = Instant.parse("2026-09-04T00:00:00Z");
  private static int passed;

  public static void main(String[] args) {
    requestLoadsFactsInOrderAndSavesTheNewWithdrawal();
    sameCommandReturnsTheExistingWithdrawalWithoutReloadingFacts();
    conflictingCommandSemanticsAreRejectedWithoutSaving();
    domainFailureIsPropagatedWithoutSaving();
    System.out.println("RequestWithdrawalHandlerTest passed: " + passed);
  }

  private static void requestLoadsFactsInOrderAndSavesTheNewWithdrawal() {
    RecordingStore store = new RecordingStore();

    Withdrawal result = new RequestWithdrawalHandler(store).handle(command("100.00"));

    check(result.id().equals("withdrawal-1"), "new withdrawal id");
    check(result.promoterId().equals("promoter-1"), "new withdrawal promoter");
    check(store.saved == result, "new withdrawal is saved");
    check(
        store.calls.equals(List.of("find", "status", "payout", "balance", "save")),
        "facts are loaded before save in order");
    passed++;
  }

  private static void sameCommandReturnsTheExistingWithdrawalWithoutReloadingFacts() {
    RecordingStore store = new RecordingStore();
    Withdrawal existing = existingWithdrawal();
    store.existing = existing;

    Withdrawal result = new RequestWithdrawalHandler(store).handle(command("100.00"));

    check(result == existing, "same command returns existing aggregate");
    check(store.calls.equals(List.of("find")), "idempotent replay does not reload facts");
    check(store.saved == null, "idempotent replay does not save");
    passed++;
  }

  private static void conflictingCommandSemanticsAreRejectedWithoutSaving() {
    RecordingStore store = new RecordingStore();
    store.existing = existingWithdrawal();

    expectThrows(
        IllegalStateException.class,
        () -> new RequestWithdrawalHandler(store).handle(command("101.00")));

    check(store.calls.equals(List.of("find")), "conflict is rejected before fact reads");
    check(store.saved == null, "conflict is not saved");

    RecordingStore changedIdStore = new RecordingStore();
    changedIdStore.existing = existingWithdrawal();
    expectThrows(
        IllegalStateException.class,
        () ->
            new RequestWithdrawalHandler(changedIdStore)
                .handle(command("withdrawal-2", Decimal4.of("100"), "100.00")));
    check(changedIdStore.calls.equals(List.of("find")), "changed id is rejected before reads");
    check(changedIdStore.saved == null, "changed id is not saved");

    RecordingStore changedMinimumStore = new RecordingStore();
    changedMinimumStore.existing = existingWithdrawal();
    expectThrows(
        IllegalStateException.class,
        () ->
            new RequestWithdrawalHandler(changedMinimumStore)
                .handle(command("withdrawal-1", Decimal4.of("101"), "100.00")));
    check(
        changedMinimumStore.calls.equals(List.of("find")),
        "changed minimum is rejected before reads");
    check(changedMinimumStore.saved == null, "changed minimum is not saved");

    RecordingStore changedRequestedAtStore = new RecordingStore();
    changedRequestedAtStore.existing = existingWithdrawal();
    expectThrows(
        IllegalStateException.class,
        () ->
            new RequestWithdrawalHandler(changedRequestedAtStore)
                .handle(
                    command(
                        "withdrawal-1",
                        Decimal4.of("100"),
                        "100.00",
                        T0.plusSeconds(1))));
    check(
        changedRequestedAtStore.calls.equals(List.of("find")),
        "changed requestedAt is rejected before reads");
    check(changedRequestedAtStore.saved == null, "changed requestedAt is not saved");
    passed++;
  }

  private static void domainFailureIsPropagatedWithoutSaving() {
    RecordingStore store = new RecordingStore();
    store.accountStatus = AccountStatus.DISABLED;

    IllegalStateException failure =
        expectThrows(
            IllegalStateException.class,
            () -> new RequestWithdrawalHandler(store).handle(command("100.00")));

    check(failure.getMessage().contains("disabled"), "domain exception is preserved");
    check(
        store.calls.equals(List.of("find", "status", "payout", "balance")),
        "domain failure happens after required reads");
    check(store.saved == null, "domain failure is not saved");
    passed++;
  }

  private static WithdrawalCommand command(String amountInput) {
    return command("withdrawal-1", Decimal4.of("100"), amountInput);
  }

  private static WithdrawalCommand command(
      String withdrawalId, Decimal4 minimumAmount, String amountInput) {
    return command(withdrawalId, minimumAmount, amountInput, T0);
  }

  private static WithdrawalCommand command(
      String withdrawalId, Decimal4 minimumAmount, String amountInput, Instant requestedAt) {
    return new WithdrawalCommand(
        "promoter-1",
        withdrawalId,
        amountInput,
        minimumAmount,
        "payout-1",
        "request-command-1",
        requestedAt);
  }

  private static Withdrawal existingWithdrawal() {
    return Withdrawal.request(
        "withdrawal-1",
        "promoter-1",
        AccountStatus.ENABLED,
        payoutAccount(),
        balances(),
        "100.00",
        Decimal4.of("100"),
        "request-command-1",
        T0);
  }

  private static PayoutAccount payoutAccount() {
    return PayoutAccount.create(
        "payout-1",
        "promoter-1",
        PayoutAccountType.BANK_CARD,
        new BankCardDetails("张三", "6222021234567890", "测试银行"),
        T0);
  }

  private static CommissionBalanceSnapshot balances() {
    return new CommissionBalanceSnapshot(
        Decimal4.ZERO, Decimal4.of("150"), Decimal4.ZERO, Decimal4.ZERO, Decimal4.ZERO);
  }

  private static void check(boolean condition, String label) {
    if (!condition) {
      throw new AssertionError(label);
    }
  }

  private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
    try {
      action.run();
    } catch (Throwable thrown) {
      if (type.isInstance(thrown)) {
        return type.cast(thrown);
      }
      throw new AssertionError("unexpected exception type", thrown);
    }
    throw new AssertionError("expected exception " + type.getSimpleName());
  }

  private static final class RecordingStore implements WithdrawalStore {

    private final List<String> calls = new ArrayList<>();
    private AccountStatus accountStatus = AccountStatus.ENABLED;
    private Withdrawal existing;
    private Withdrawal saved;

    @Override
    public Optional<Withdrawal> findByRequestCommandId(String promoterId, String commandId) {
      calls.add("find");
      return Optional.ofNullable(existing);
    }

    @Override
    public AccountStatus loadPromoterAccountStatus(String promoterId) {
      calls.add("status");
      return accountStatus;
    }

    @Override
    public PayoutAccount loadPayoutAccount(String promoterId, String payoutAccountId) {
      calls.add("payout");
      return payoutAccount();
    }

    @Override
    public CommissionBalanceSnapshot loadCommissionBalance(String promoterId) {
      calls.add("balance");
      return balances();
    }

    @Override
    public void save(Withdrawal withdrawal) {
      calls.add("save");
      saved = withdrawal;
    }
  }
}
