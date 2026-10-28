package com.xzwl.cloud.promotion.application.withdrawal;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.commission.CommissionBalanceSnapshot;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.payout.BankCardDetails;
import com.xzwl.cloud.promotion.domain.payout.PayoutAccount;
import com.xzwl.cloud.promotion.domain.payout.PayoutAccountType;
import com.xzwl.cloud.promotion.domain.withdrawal.Withdrawal;
import com.xzwl.cloud.promotion.domain.withdrawal.WithdrawalBalanceActionType;
import com.xzwl.cloud.promotion.domain.withdrawal.WithdrawalStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class RejectWithdrawalHandlerTest {

  private static final Instant T0 = Instant.parse("2026-09-04T00:00:00Z");

  public static void main(String[] args) {
    rejectsLoadedWithdrawalAndSavesIt();
    System.out.println("RejectWithdrawalHandlerTest passed: 1");
  }

  private static void rejectsLoadedWithdrawalAndSavesIt() {
    RecordingStore store = new RecordingStore(validWithdrawal());

    var result =
        new RejectWithdrawalHandler(store)
            .handle(
                new RejectWithdrawalCommand(
                    "withdrawal-1", "reject-1", "admin-1", "too late", T0.plusSeconds(1)));

    check(result.status() == WithdrawalStatus.REJECTED, "rejection result status");
    check(
        result.balanceAction().orElseThrow().type() == WithdrawalBalanceActionType.RELEASE_RESERVATION,
        "rejection releases reservation");
    check(store.saved == store.withdrawal, "rejected withdrawal is saved");
    check(store.calls.equals(List.of("load", "save")), "load and save order");
  }

  private static Withdrawal validWithdrawal() {
    return Withdrawal.request(
        "withdrawal-1",
        "promoter-1",
        AccountStatus.ENABLED,
        PayoutAccount.create(
            "payout-1",
            "promoter-1",
            PayoutAccountType.BANK_CARD,
            new BankCardDetails("张三", "6222021234567890", "测试银行"),
            T0),
        new CommissionBalanceSnapshot(
            Decimal4.ZERO, Decimal4.of("150"), Decimal4.ZERO, Decimal4.ZERO, Decimal4.ZERO),
        "100.00",
        Decimal4.of("100"),
        "request-command-1",
        T0);
  }

  private static void check(boolean condition, String label) {
    if (!condition) {
      throw new AssertionError(label);
    }
  }

  private static final class RecordingStore implements WithdrawalRejectionStore {

    private final List<String> calls = new ArrayList<>();
    private final Withdrawal withdrawal;
    private Withdrawal saved;

    private RecordingStore(Withdrawal withdrawal) {
      this.withdrawal = withdrawal;
    }

    @Override
    public Withdrawal load(String withdrawalId) {
      calls.add("load");
      check(withdrawal.id().equals(withdrawalId), "withdrawal id is loaded");
      return withdrawal;
    }

    @Override
    public void save(Withdrawal withdrawal) {
      calls.add("save");
      saved = withdrawal;
    }
  }
}
