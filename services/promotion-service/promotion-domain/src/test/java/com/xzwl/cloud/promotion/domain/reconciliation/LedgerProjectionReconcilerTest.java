package com.xzwl.cloud.promotion.domain.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.commission.CommissionAccount;
import com.xzwl.cloud.promotion.domain.commission.CommissionBalanceSnapshot;
import com.xzwl.cloud.promotion.domain.commission.CommissionEntry;
import com.xzwl.cloud.promotion.domain.commission.CommissionEntryReason;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.reconciliation.LedgerProjectionReconciler.Dimension;
import com.xzwl.cloud.promotion.domain.reconciliation.LedgerProjectionReconciler.LedgerKind;
import com.xzwl.cloud.promotion.domain.reconciliation.LedgerProjectionReconciler.Preview;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import com.xzwl.cloud.promotion.domain.reward.PointRewardAccount;
import com.xzwl.cloud.promotion.domain.reward.PointRewardSnapshot;
import com.xzwl.cloud.promotion.domain.reward.ReferralPointEntitlement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LedgerProjectionReconcilerTest {

  private static final Instant T0 = Instant.parse("2026-09-04T00:00:00Z");

  @Test
  void previewsEveryCommissionProjectionDifferenceFromRehydratedLedger() {
    CommissionAccount account = commissionAccount();
    List<CommissionEntry> ledger = new ArrayList<>(account.entries());
    CommissionBalanceSnapshot actual =
        new CommissionBalanceSnapshot(
            amount("4"), amount("1"), amount("5"), amount("2"), Decimal4.ZERO);

    Preview<CommissionEntry> preview =
        LedgerProjectionReconciler.previewCommission("promoter", ledger, actual);

    assertThat(preview.ledgerKind()).isEqualTo(LedgerKind.COMMISSION);
    assertThat(preview.subjectId()).isEqualTo("promoter");
    assertThat(preview.executable()).isFalse();
    assertThat(preview.differences())
        .extracting(LedgerProjectionReconciler.Difference::dimension)
        .containsExactly(
            Dimension.FROZEN,
            Dimension.AVAILABLE,
            Dimension.WITHDRAWING,
            Dimension.WITHDRAWN,
            Dimension.CASH_DEBT);
    assertDifference(preview, 0, Dimension.FROZEN, "5", "4", "1");
    assertDifference(preview, 1, Dimension.AVAILABLE, "0", "1", "-1");
    assertDifference(preview, 2, Dimension.WITHDRAWING, "4", "5", "-1");
    assertDifference(preview, 3, Dimension.WITHDRAWN, "3", "2", "1");
    assertDifference(preview, 4, Dimension.CASH_DEBT, "1", "0", "1");

    ledger.clear();
    assertThat(preview.inputEntries()).hasSameElementsAs(account.entries());
    assertThatThrownBy(() -> preview.inputEntries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> preview.differences().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(account.currentSnapshot())
        .isEqualTo(new CommissionBalanceSnapshot(amount("5"), Decimal4.ZERO, amount("4"), amount("3"), amount("1")));
  }

  @Test
  void previewsEveryPointProjectionDifferenceFromRehydratedLedger() {
    PointRewardAccount account = pointRewardAccount("beneficiary");
    PointRewardSnapshot actual =
        new PointRewardSnapshot(amount("9"), amount("9"), amount("5"));

    Preview<ReferralPointEntitlement> preview =
        LedgerProjectionReconciler.previewPointReward(
            "beneficiary", account.entries(), actual);

    assertThat(preview.ledgerKind()).isEqualTo(LedgerKind.POINT_REWARD);
    assertThat(preview.subjectId()).isEqualTo("beneficiary");
    assertThat(preview.executable()).isFalse();
    assertThat(preview.differences())
        .extracting(LedgerProjectionReconciler.Difference::dimension)
        .containsExactly(
            Dimension.CUMULATIVE_GRANTED,
            Dimension.CUMULATIVE_REVERSED,
            Dimension.POINT_DEBT);
    assertDifference(preview, 0, Dimension.CUMULATIVE_GRANTED, "10", "9", "1");
    assertDifference(preview, 1, Dimension.CUMULATIVE_REVERSED, "8", "9", "-1");
    assertDifference(preview, 2, Dimension.POINT_DEBT, "6", "5", "1");
  }

  @Test
  void returnsEmptyDifferencesWhenStoredProjectionMatchesLedger() {
    CommissionAccount commission = commissionAccount();
    PointRewardAccount points = pointRewardAccount("beneficiary");

    assertThat(
            LedgerProjectionReconciler.previewCommission(
                    "promoter", commission.entries(), commission.currentSnapshot())
                .differences())
        .isEmpty();
    assertThat(
            LedgerProjectionReconciler.previewPointReward(
                    "beneficiary", points.entries(), points.currentSnapshot())
                .differences())
        .isEmpty();
  }

  @Test
  void rejectsBrokenOrCrossBeneficiaryLedgersThroughAggregateRehydration() {
    CommissionAccount otherCommission = CommissionAccount.create("other-promoter");
    otherCommission.freezeCommission(
        "freeze", "order", "lot", amount("1"), 1, CashTier.LEVEL_1, T0);

    assertThatThrownBy(
            () ->
                LedgerProjectionReconciler.previewCommission(
                    "promoter", otherCommission.entries(), CommissionBalanceSnapshot.zero()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("beneficiary");

    PointRewardAccount points = pointRewardAccount("beneficiary");
    assertThatThrownBy(
            () ->
                LedgerProjectionReconciler.previewPointReward(
                    "beneficiary",
                    List.of(points.entries().get(1)),
                    PointRewardSnapshot.zero()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence");

    PointRewardAccount otherPoints = pointRewardAccount("other-beneficiary");
    assertThatThrownBy(
            () ->
                LedgerProjectionReconciler.previewPointReward(
                    "beneficiary", otherPoints.entries(), PointRewardSnapshot.zero()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("beneficiary");
  }

  private static CommissionAccount commissionAccount() {
    CommissionAccount account = CommissionAccount.create("promoter");
    account.freezeCommission(
        "freeze", "order", "lot", amount("20"), 1, CashTier.LEVEL_1, T0);
    account.releaseCommission(
        "release", "order", "lot", "allocation", amount("15"), T0.plusSeconds(1));
    account.requestWithdrawal("request-paid", "withdrawal-paid", amount("3"), T0.plusSeconds(2));
    account.markWithdrawalPaid(
        "paid",
        "withdrawal-paid",
        CommissionEntryReason.WITHDRAWAL_OFFLINE_CONFIRMED_PAID,
        T0.plusSeconds(3));
    account.requestWithdrawal(
        "request-pending", "withdrawal-pending", amount("4"), T0.plusSeconds(4));
    account.reverseRelease("reverse", "allocation", amount("9"), T0.plusSeconds(5));
    return account;
  }

  private static PointRewardAccount pointRewardAccount(String beneficiary) {
    PointRewardAccount account = PointRewardAccount.create(beneficiary);
    account.grant(
        "grant",
        "entitlement",
        "settlement",
        "allocation",
        "lot",
        "consumer",
        1,
        CashTier.NONE,
        amount("10"),
        T0);
    account.reverse(
        "reverse",
        "entitlement",
        "settlement",
        "allocation",
        "lot",
        "consumer",
        1,
        CashTier.NONE,
        amount("8"),
        amount("2"),
        T0.plusSeconds(1));
    return account;
  }

  private static void assertDifference(
      Preview<?> preview,
      int index,
      Dimension dimension,
      String expected,
      String actual,
      String delta) {
    LedgerProjectionReconciler.Difference difference = preview.differences().get(index);
    assertThat(difference.ledgerKind()).isEqualTo(preview.ledgerKind());
    assertThat(difference.subjectId()).isEqualTo(preview.subjectId());
    assertThat(difference.dimension()).isEqualTo(dimension);
    assertThat(difference.expected4()).isEqualTo(amount(expected));
    assertThat(difference.actual4()).isEqualTo(amount(actual));
    assertThat(difference.delta4()).isEqualTo(amount(delta));
  }

  private static Decimal4 amount(String value) {
    return Decimal4.parse(value);
  }
}
