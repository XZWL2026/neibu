package com.xzwl.cloud.promotion.application.reconciliation;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.reconciliation.LedgerProjectionReconciler;
import com.xzwl.cloud.promotion.domain.reward.PointRewardSnapshot;
import java.util.List;

public final class ReconcileLedgerHandlerTest {

  public static void main(String[] args) {
    LedgerProjectionReconciler.Preview<?> preview = new ReconcileLedgerHandler()
        .previewPointReward("beneficiary-1", List.of(), new PointRewardSnapshot(
            Decimal4.of("5"), Decimal4.ZERO, Decimal4.ZERO));

    check(!preview.executable(), "reconciliation is preview-only");
    check(preview.differences().size() == 1, "one projection difference is reported");
    check(preview.differences().getFirst().expected4().equals(Decimal4.ZERO), "expected projection is zero");
    check(preview.differences().getFirst().actual4().equals(Decimal4.of("5")), "actual projection is preserved");
    System.out.println("ReconcileLedgerHandlerTest passed: 1");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError(label);
  }
}
