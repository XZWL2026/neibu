package com.xzwl.cloud.promotion.infrastructure;

import com.xzwl.cloud.promotion.application.commission.CommissionStore;
import com.xzwl.cloud.promotion.domain.commission.CommissionAccount;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Pre-development persistence adapter for promoter commission ledgers. */
public final class InMemoryCommissionPersistence implements CommissionStore {

  private final Map<String, CommissionAccount> accounts = new HashMap<>();

  @Override
  public synchronized CommissionAccount load(String promoterUserId) {
    String id = requireId(promoterUserId);
    return accounts.computeIfAbsent(id, CommissionAccount::create);
  }

  @Override
  public synchronized void save(CommissionAccount account) {
    CommissionAccount requested = Objects.requireNonNull(account, "account");
    for (Map.Entry<String, CommissionAccount> entry : accounts.entrySet()) {
      if (entry.getValue() == requested) {
        entry.setValue(requested);
        return;
      }
    }
    throw new IllegalArgumentException("commission account must be loaded before save");
  }

  private static String requireId(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("promoterUserId must not be blank");
    }
    return value;
  }
}
