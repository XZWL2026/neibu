package com.xzwl.cloud.billing.application.credit;

import com.xzwl.cloud.billing.domain.wallet.WalletBalance;
import java.util.Optional;

/** Port whose adapter must commit the balance and record in one transaction. */
public interface AdminCreditStore {

  Optional<AdminCreditResult> findByRequestId(String requestId);

  WalletBalance loadBalance(String walletId);

  void saveAtomically(AdminCreditRecord record, WalletBalance newBalance);

  default AdminCreditResult creditAtomically(AdminCreditRecord record) {
    WalletBalance current = loadBalance(record.walletId());
    if (!current.walletId().equals(record.walletId())) throw new IllegalStateException("wallet mismatch");
    WalletBalance updated = current.credit(record.points4());
    saveAtomically(record, updated);
    return new AdminCreditResult(updated, record);
  }
}
