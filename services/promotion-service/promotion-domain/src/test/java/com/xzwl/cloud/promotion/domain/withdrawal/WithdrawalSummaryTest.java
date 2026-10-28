package com.xzwl.cloud.promotion.domain.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.commission.CommissionBalanceSnapshot;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.payout.BankCardDetails;
import com.xzwl.cloud.promotion.domain.payout.PayoutAccount;
import com.xzwl.cloud.promotion.domain.payout.PayoutAccountType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WithdrawalSummaryTest {

  private static final Instant T0 = Instant.parse("2026-09-04T00:00:00Z");

  @Test
  void inProgressIncludesWithdrawingAndPendingWhileCumulativePaidOnlyIncludesPaid() {
    Withdrawal withdrawing = withdrawal("withdrawing");
    Withdrawal pending = withdrawal("pending");
    pending.approve("approve-pending", "admin-1", T0.plusSeconds(1));
    Withdrawal paid = withdrawal("paid");
    paid.approve("approve-paid", "admin-1", T0.plusSeconds(1));
    paid.markPaid("mark-paid", "admin-1", T0.plusSeconds(2));
    Withdrawal rejected = withdrawal("rejected");
    rejected.reject("reject", "admin-1", "资料不符", T0.plusSeconds(1));

    WithdrawalSummary summary =
        WithdrawalSummary.from(List.of(withdrawing, pending, paid, rejected));

    assertThat(summary.inProgress()).isEqualTo(amount("200"));
    assertThat(summary.cumulativePaid()).isEqualTo(amount("100"));
  }

  private static Withdrawal withdrawal(String id) {
    PayoutAccount payout =
        PayoutAccount.create(
            "payout-" + id,
            "promoter-1",
            PayoutAccountType.BANK_CARD,
            new BankCardDetails("张三", "6222021234567890", "测试银行"),
            T0);
    return Withdrawal.request(
        "withdrawal-" + id,
        "promoter-1",
        AccountStatus.ENABLED,
        payout,
        new CommissionBalanceSnapshot(
            Decimal4.ZERO, amount("150"), Decimal4.ZERO, Decimal4.ZERO, Decimal4.ZERO),
        "100.00",
        amount("100"),
        "request-" + id,
        T0);
  }

  private static Decimal4 amount(String value) {
    return Decimal4.of(value);
  }
}
