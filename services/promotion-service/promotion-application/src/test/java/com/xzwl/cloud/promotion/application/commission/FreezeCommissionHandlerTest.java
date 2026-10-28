package com.xzwl.cloud.promotion.application.commission;

import com.xzwl.cloud.promotion.domain.commission.CommissionAccount;
import com.xzwl.cloud.promotion.domain.commission.CommissionEntry;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.time.Instant;

public final class FreezeCommissionHandlerTest {

  public static void main(String[] args) {
    freezeIsPersistedAndReplayedIdempotently();
    System.out.println("FreezeCommissionHandlerTest passed: 1");
  }

  private static void freezeIsPersistedAndReplayedIdempotently() {
    RecordingStore store = new RecordingStore();
    FreezeCommissionHandler handler = new FreezeCommissionHandler(store);
    FreezeCommissionCommand command =
        new FreezeCommissionCommand(
            "promoter-1",
            "event-1",
            "order-1",
            "lot-1",
            Decimal4.of("3.0000"),
            1,
            CashTier.LEVEL_1,
            Instant.parse("2026-09-07T00:00:00Z"));

    CommissionEntry first = handler.handle(command);
    CommissionEntry replay = handler.handle(command);

    check(first == replay, "domain replay returns the original freeze fact");
    check(store.account.entries().size() == 1, "persistence contains one freeze fact");
    check(store.saves == 2, "handler persists both first write and idempotent replay");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }

  private static final class RecordingStore implements CommissionStore {
    private final CommissionAccount account = CommissionAccount.create("promoter-1");
    private int saves;

    @Override
    public CommissionAccount load(String promoterUserId) {
      if (!"promoter-1".equals(promoterUserId)) throw new AssertionError("unexpected promoter");
      return account;
    }

    @Override
    public void save(CommissionAccount account) {
      saves++;
      if (account != this.account) throw new AssertionError("unexpected account instance");
    }
  }
}
