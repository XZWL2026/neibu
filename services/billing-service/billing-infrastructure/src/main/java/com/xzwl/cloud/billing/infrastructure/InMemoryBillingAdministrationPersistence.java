package com.xzwl.cloud.billing.infrastructure;

import com.xzwl.cloud.billing.application.credit.AdminCreditRecord;
import com.xzwl.cloud.billing.application.credit.AdminCreditResult;
import com.xzwl.cloud.billing.application.credit.AdminCreditStore;
import com.xzwl.cloud.billing.application.pricing.PricingPolicyStore;
import com.xzwl.cloud.billing.domain.pricing.PricingCatalog;
import com.xzwl.cloud.billing.domain.wallet.WalletBalance;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Pre-development persistence for administrator credits and the active pricing catalog. */
public final class InMemoryBillingAdministrationPersistence
    implements AdminCreditStore, PricingPolicyStore {

  private final Map<String, WalletBalance> balances = new HashMap<>();
  private final Map<String, AdminCreditResult> creditsByRequest = new HashMap<>();
  private PricingCatalog catalog = PricingCatalog.empty();

  public synchronized void openWallet(WalletBalance balance) {
    WalletBalance requested = Objects.requireNonNull(balance, "balance");
    if (balances.putIfAbsent(requested.walletId(), requested) != null) {
      throw new IllegalStateException("wallet already exists");
    }
  }

  @Override
  public synchronized Optional<AdminCreditResult> findByRequestId(String requestId) {
    return Optional.ofNullable(creditsByRequest.get(requireId(requestId, "requestId")));
  }

  @Override
  public synchronized WalletBalance loadBalance(String walletId) {
    WalletBalance balance = balances.get(requireId(walletId, "walletId"));
    if (balance == null) throw new IllegalArgumentException("unknown wallet");
    return balance;
  }

  @Override
  public synchronized void saveAtomically(AdminCreditRecord record, WalletBalance newBalance) {
    AdminCreditRecord requestedRecord = Objects.requireNonNull(record, "record");
    WalletBalance requestedBalance = Objects.requireNonNull(newBalance, "newBalance");
    WalletBalance current = loadBalance(requestedRecord.walletId());
    if (!requestedBalance.walletId().equals(requestedRecord.walletId())
        || requestedBalance.version() != current.version() + 1) {
      throw new IllegalStateException("credit balance version does not follow stored balance");
    }
    AdminCreditResult result = new AdminCreditResult(requestedBalance, requestedRecord);
    if (creditsByRequest.putIfAbsent(requestedRecord.requestId(), result) != null) {
      throw new IllegalStateException("credit request already exists");
    }
    balances.put(requestedBalance.walletId(), requestedBalance);
  }

  @Override
  public synchronized PricingCatalog load() {
    return catalog;
  }

  @Override
  public synchronized void save(PricingCatalog updated) {
    PricingCatalog requested = Objects.requireNonNull(updated, "catalog");
    if (requested.version() != catalog.version() + 1) {
      throw new IllegalStateException("pricing catalog version does not follow stored version");
    }
    catalog = requested;
  }

  private static String requireId(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
