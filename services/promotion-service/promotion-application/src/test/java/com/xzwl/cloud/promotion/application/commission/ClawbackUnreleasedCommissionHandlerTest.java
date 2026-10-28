package com.xzwl.cloud.promotion.application.commission;

import com.xzwl.cloud.promotion.domain.commission.CommissionAccount;
import com.xzwl.cloud.promotion.domain.commission.CommissionBalanceSnapshot;
import com.xzwl.cloud.promotion.domain.commission.CommissionEntry;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ClawbackUnreleasedCommissionHandlerTest {

  private static final Instant T0 = Instant.parse("2026-09-05T00:00:00Z");

  public static void main(String[] args) {
    clawsBackUnreleasedCommissionThroughTheAccountPort();
    System.out.println("ClawbackUnreleasedCommissionHandlerTest passed: 1");
  }

  private static void clawsBackUnreleasedCommissionThroughTheAccountPort() {
    CommissionAccount account = CommissionAccount.create("promoter-1");
    account.freezeCommission(
        "freeze-event", "order-1", "lot-1", Decimal4.of("5.0000"), 7L,
        CashTier.LEVEL_1, T0);
    RecordingStore store = new RecordingStore(account);

    CommissionEntry result =
        new ClawbackUnreleasedCommissionHandler(store)
            .handle(
                new ClawbackUnreleasedCommissionCommand(
                    "promoter-1", "refund-event", "order-1", "lot-1",
                    Decimal4.of("5.0000"), T0.plusSeconds(1)));

    CommissionBalanceSnapshot snapshot = account.currentSnapshot();
    check(result.amount4().equals(Decimal4.of("5.0000")), "refund amount is recorded");
    check(snapshot.frozen().equals(Decimal4.ZERO), "unreleased commission is clawed back");
    check(snapshot.available().equals(Decimal4.ZERO), "no available commission is created");
    check(account.entries().size() == 2, "clawback ledger entry is appended");
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
