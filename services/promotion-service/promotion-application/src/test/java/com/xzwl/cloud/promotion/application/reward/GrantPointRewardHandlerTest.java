package com.xzwl.cloud.promotion.application.reward;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import com.xzwl.cloud.promotion.domain.reward.PointRewardAccount;
import com.xzwl.cloud.promotion.domain.reward.PointRewardWriteResult;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class GrantPointRewardHandlerTest {

  public static void main(String[] args) {
    grantsRewardAndDoesNotDuplicateAnIdempotentReplay();
    System.out.println("GrantPointRewardHandlerTest passed: 1");
  }

  private static void grantsRewardAndDoesNotDuplicateAnIdempotentReplay() {
    RecordingStore store = new RecordingStore();
    GrantPointRewardHandler handler = new GrantPointRewardHandler(store);
    GrantPointRewardCommand command = new GrantPointRewardCommand(
        "beneficiary-1", "grant-event-1", "entitlement-1", "settlement-1", "allocation-1",
        "funding-lot-1", "consumer-1", 8, CashTier.LEVEL_1, Decimal4.of("10"),
        Instant.parse("2026-09-05T00:00:00Z"));

    PointRewardWriteResult first = handler.handle(command);
    PointRewardWriteResult replay = handler.handle(command);

    check(first.equals(replay), "replay returns same reward facts");
    check(first.netRewardToCredit().equals(Decimal4.of("10")), "net reward is returned for billing");
    check(store.account.entries().size() == 1, "replay does not append a second ledger entry");
    check(store.saveCalls == 1, "replay does not save twice");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }

  private static final class RecordingStore implements PointRewardStore {
    private final PointRewardAccount account = PointRewardAccount.create("beneficiary-1");
    private int saveCalls;

    @Override
    public PointRewardAccount load(String beneficiaryUserId) {
      return account;
    }

    @Override
    public void save(PointRewardAccount saved) {
      saveCalls++;
    }
  }
}
