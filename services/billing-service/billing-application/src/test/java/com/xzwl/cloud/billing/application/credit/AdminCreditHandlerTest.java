package com.xzwl.cloud.billing.application.credit;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.wallet.WalletBalance;
import java.time.Instant;
import java.util.Optional;

public final class AdminCreditHandlerTest {

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() throws Exception { main(new String[0]); }

  public static void main(String[] args) {
    creditsBalanceAndRecordAtomicallyAndReplaysByRequestId();
    rejectsConflictingReplayAndNonPositiveCredits();
    System.out.println("AdminCreditHandlerTest passed: 2");
  }

  private static void creditsBalanceAndRecordAtomicallyAndReplaysByRequestId() {
    RecordingStore store = new RecordingStore();
    AdminCreditHandler handler = new AdminCreditHandler(store);
    AdminCreditCommand command = new AdminCreditCommand(
        "credit-1", "wallet-1", "key-1", Decimal4.of("12.34569"), "admin-1",
        Instant.parse("2026-09-05T00:00:00Z"), "补偿");

    AdminCreditResult first = handler.handle(command);
    AdminCreditResult replay = handler.handle(command);

    check(first.record().equals(replay.record()), "exact replay returns original record");
    check(first.balance().equals(replay.balance()), "exact replay returns original balance");
    check(first.balance().availablePoints4().equals(Decimal4.of("12.3456")), "balance is truncated");
    check(first.record().points4().equals(Decimal4.of("12.3456")), "record amount is truncated");
    check(store.saveCalls == 1, "balance and record are saved once");
  }

  private static void rejectsConflictingReplayAndNonPositiveCredits() {
    RecordingStore store = new RecordingStore();
    AdminCreditHandler handler = new AdminCreditHandler(store);
    AdminCreditCommand command = new AdminCreditCommand(
        "credit-1", "wallet-1", "key-1", Decimal4.of("1"), "admin-1",
        Instant.parse("2026-09-05T00:00:00Z"), "补偿");
    handler.handle(command);

    expectThrows(IllegalStateException.class, () -> handler.handle(new AdminCreditCommand(
        "credit-1", "wallet-1", "key-1", Decimal4.of("2"), "admin-1",
        command.at(), "补偿")));
    expectThrows(IllegalArgumentException.class, () -> handler.handle(new AdminCreditCommand(
        "credit-2", "wallet-1", "key-1", Decimal4.zero(), "admin-1",
        command.at(), "补偿")));
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }

  private static <T extends Throwable> T expectThrows(Class<T> type, Runnable action) {
    try {
      action.run();
    } catch (Throwable thrown) {
      if (type.isInstance(thrown)) return type.cast(thrown);
      throw new AssertionError("unexpected exception type", thrown);
    }
    throw new AssertionError("expected exception " + type.getSimpleName());
  }

  private static final class RecordingStore implements AdminCreditStore {
    private AdminCreditResult saved;
    private WalletBalance balance = WalletBalance.zero("wallet-1");
    private int saveCalls;

    @Override
    public Optional<AdminCreditResult> findByRequestId(String requestId) {
      return saved != null && saved.record().requestId().equals(requestId)
          ? Optional.of(saved)
          : Optional.empty();
    }

    @Override
    public WalletBalance loadBalance(String walletId) {
      return balance;
    }

    @Override
    public void saveAtomically(AdminCreditRecord savedRecord, WalletBalance newBalance) {
      balance = newBalance;
      saved = new AdminCreditResult(newBalance, savedRecord);
      saveCalls++;
    }
  }
}
