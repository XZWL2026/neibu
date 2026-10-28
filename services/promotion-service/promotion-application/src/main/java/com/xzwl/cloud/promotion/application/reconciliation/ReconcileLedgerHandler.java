package com.xzwl.cloud.promotion.application.reconciliation;

import com.xzwl.cloud.promotion.domain.commission.CommissionBalanceSnapshot;
import com.xzwl.cloud.promotion.domain.commission.CommissionEntry;
import com.xzwl.cloud.promotion.domain.reconciliation.LedgerProjectionReconciler;
import com.xzwl.cloud.promotion.domain.reward.PointRewardSnapshot;
import com.xzwl.cloud.promotion.domain.reward.ReferralPointEntitlement;
import java.util.List;
import java.util.Objects;

/** Exposes read-only promotion ledger reconciliation previews; it never repairs projections. */
public final class ReconcileLedgerHandler {

  public LedgerProjectionReconciler.Preview<CommissionEntry> previewCommission(
      String promoterUserId,
      List<CommissionEntry> entries,
      CommissionBalanceSnapshot actual) {
    return LedgerProjectionReconciler.previewCommission(
        promoterUserId, Objects.requireNonNull(entries, "entries"), actual);
  }

  public LedgerProjectionReconciler.Preview<ReferralPointEntitlement> previewPointReward(
      String beneficiaryUserId,
      List<ReferralPointEntitlement> entries,
      PointRewardSnapshot actual) {
    return LedgerProjectionReconciler.previewPointReward(
        beneficiaryUserId, Objects.requireNonNull(entries, "entries"), actual);
  }
}
