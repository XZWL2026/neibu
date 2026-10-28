package com.xzwl.cloud.access.domain.key;

import java.util.Objects;

/** Account-side rules for the single primary key created by the first successful recharge. */
public final class AccountAccess {

  private final String accountId;
  private AccountType accountType = AccountType.UNSET;
  private PrimaryKey primaryKey;

  private AccountAccess(String accountId) {
    this.accountId = requireId(accountId, "accountId");
  }

  public static AccountAccess create(String accountId) {
    return new AccountAccess(accountId);
  }

  public String accountId() {
    return accountId;
  }

  public AccountType accountType() {
    return accountType;
  }

  public PrimaryKey primaryKey() {
    return primaryKey;
  }

  public synchronized PrimaryKey onFirstSuccessfulRecharge(
      AccountType requestedType, KeyType keyType, String secret) {
    Objects.requireNonNull(requestedType, "requestedType");
    Objects.requireNonNull(keyType, "keyType");
    if (requestedType == AccountType.UNSET) {
      throw new IllegalArgumentException("first recharge must choose an account type");
    }
    if (accountType != AccountType.UNSET) {
      if (accountType != requestedType) {
        throw new IllegalStateException("account type is already locked");
      }
      return primaryKey;
    }
    if (requestedType == AccountType.API && keyType != KeyType.API) {
      throw new IllegalArgumentException("API account requires an API primary key");
    }
    PrimaryKey created = new PrimaryKey(accountId + "-primary", accountId, keyType, secret);
    accountType = requestedType;
    primaryKey = created;
    return created;
  }

  private static String requireId(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
