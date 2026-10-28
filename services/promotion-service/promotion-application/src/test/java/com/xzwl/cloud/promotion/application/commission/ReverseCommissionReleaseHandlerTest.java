package com.xzwl.cloud.promotion.application.commission;

import com.xzwl.cloud.promotion.domain.commission.CommissionAccount;
import com.xzwl.cloud.promotion.domain.commission.CommissionBalanceSnapshot;
import com.xzwl.cloud.promotion.domain.commission.CommissionWriteResult;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ReverseCommissionReleaseHandlerTest {

  private static final Instant T0 = Instant.parse("2026-09-05T00:00:00Z");

  public static void main(String[] args) {
    reversesReleasedCommissionThroughTheAccountPort();
    System.out.println("ReverseCommissionReleaseHandlerTest passed: 1");
  }

  private static void reversesReleasedCommissionThroughTheAccountPort() {
    CommissionAccount account = CommissionAccount.create("promoter-1");
    account.freezeCommission(
        "freeze-event", "order-1", "lot-1", Decimal4.of("5.0000"), 7L,
        CashTier.LEVEL_1, T0);
    account.releaseCommission(
        "release-event", "order-1", "lot-1", "allocation-1", Decimal4.of("5.0000"),
        T0.plusSeconds(1));
    RecordingStore store = new RecordingStore(account);

    CommissionWriteResult result =
        new ReverseCommissionReleaseHandler(store)
            .handle(
                new ReverseCommissionReleaseCommand(
                    "promoter-1", "reverse-event", "allocation-1", Decimal4.of("5.0000"),
                    T0.plusSeconds(2)));

    CommissionBalanceSnapshot snapshot = result.snapshot();
    check(snapshot.available().equals(Decimal4.ZERO), "released commission is reversed");
    check(snapshot.cashDebt().equals(Decimal4.ZERO), "no debt when funds are available");
    check(account.entries().size() == 3, "reversal ledger entry is appended");
    check(store.calls.equals(List.of("load", "save")), "load and save order");
    check(store.saved == account, "updated account is saved");
  }

  private static void check(boolean condition, String label) {
    if (!condition) {
      throw new AssertionError(label);
    }
  }

  private static final class RecordingStore implements CommissionStore {

    private final List<String> calls = new ArrayList<>();
    private final CommissionAccount account;
    private CommissionAccount saved;

    private RecordingStore(CommissionAccount account) {
      this.account = account;
    }

    @Override
    public CommissionAccount load(String promoterUserId) {
      calls.add("load");
      check(promoterUserId.equals("promoter-1"), "commission account id is loaded");
      return account;
    }

    @Override
    public void save(CommissionAccount account) {
      calls.add("save");
      saved = account;
    }
  }
}
