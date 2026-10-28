package com.xzwl.cloud.access.domain.key;

public final class AccountAccessTest {

  @org.junit.jupiter.api.Test
  void runsExistingBehaviorChecks() { main(new String[0]); }

  public static void main(String[] args) {
    firstRechargeLocksTypeAndCreatesOnlyOnePrimaryKey();
    rejectsAnApiAccountWithAMediaPrimaryKey();
    System.out.println("AccountAccessTest passed: 2");
  }

  private static void firstRechargeLocksTypeAndCreatesOnlyOnePrimaryKey() {
    AccountAccess account = AccountAccess.create("account-1");
    PrimaryKey first = account.onFirstSuccessfulRecharge(
        AccountType.PERSONAL, KeyType.MEDIA,
        "sk-_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    PrimaryKey replay = account.onFirstSuccessfulRecharge(
        AccountType.PERSONAL, KeyType.MEDIA,
        "sk-_ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

    check(account.accountType() == AccountType.PERSONAL, "account type is locked");
    check(first.equals(replay), "later recharge does not rotate the primary key");
    check(account.primaryKey().equals(first), "primary key remains stable");
  }

  private static void rejectsAnApiAccountWithAMediaPrimaryKey() {
    expectThrows(
        IllegalArgumentException.class,
        () ->
            AccountAccess.create("account-2")
                .onFirstSuccessfulRecharge(
                    AccountType.API,
                    KeyType.MEDIA,
                    "sk-_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
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
}
