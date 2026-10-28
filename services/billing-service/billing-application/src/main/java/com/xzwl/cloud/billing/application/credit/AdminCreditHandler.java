package com.xzwl.cloud.billing.application.credit;

import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.wallet.WalletBalance;
import java.util.Objects;

/** Applies an idempotent administrator credit with an atomic persistence boundary. */
public final class AdminCreditHandler {

  private final AdminCreditStore store;

  public AdminCreditHandler(AdminCreditStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public AdminCreditResult handle(AdminCreditCommand command) {
    AdminCreditCommand requested = Objects.requireNonNull(command, "command");
    java.util.Optional<AdminCreditResult> existing = store.findByRequestId(requested.requestId());
    if (existing.isPresent()) {
      AdminCreditResult replay = existing.orElseThrow();
      if (!sameRequest(replay.record(), requested)) {
        throw new IllegalStateException("request id was replayed with different credit facts");
      }
      return replay;
    }

    AdminCreditRecord record = new AdminCreditRecord(
        requested.requestId(), requested.walletId(), requested.keyId(), requested.points4(),
        requested.adminId(), requested.at(), requested.remark());
    return store.creditAtomically(record);
  }

  private static boolean sameRequest(AdminCreditRecord record, AdminCreditCommand command) {
    return record.walletId().equals(command.walletId())
        && record.keyId().equals(command.keyId())
        && record.points4().equals(command.points4())
        && record.adminId().equals(command.adminId())
        && record.remark().equals(command.remark());
  }
}
