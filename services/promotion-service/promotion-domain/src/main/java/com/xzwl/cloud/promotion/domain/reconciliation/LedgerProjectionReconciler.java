package com.xzwl.cloud.promotion.domain.reconciliation;

import com.xzwl.cloud.promotion.domain.commission.CommissionAccount;
import com.xzwl.cloud.promotion.domain.commission.CommissionBalanceSnapshot;
import com.xzwl.cloud.promotion.domain.commission.CommissionEntry;
import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.reward.PointRewardAccount;
import com.xzwl.cloud.promotion.domain.reward.PointRewardSnapshot;
import com.xzwl.cloud.promotion.domain.reward.ReferralPointEntitlement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds immutable, non-executable previews of ledger projection differences. */
public final class LedgerProjectionReconciler {

  private LedgerProjectionReconciler() {}

  public static Preview<CommissionEntry> previewCommission(
      String beneficiaryPromoterUserId,
      List<CommissionEntry> entries,
      CommissionBalanceSnapshot actual) {
    List<CommissionEntry> immutableEntries =
        List.copyOf(Objects.requireNonNull(entries, "entries"));
    CommissionBalanceSnapshot expected =
        CommissionAccount.rehydrate(beneficiaryPromoterUserId, immutableEntries).currentSnapshot();
    Objects.requireNonNull(actual, "actual");

    List<Difference> differences = new ArrayList<>();
    addDifference(
        differences,
        LedgerKind.COMMISSION,
        beneficiaryPromoterUserId,
        Dimension.FROZEN,
        expected.frozen(),
        actual.frozen());
    addDifference(
        differences,
        LedgerKind.COMMISSION,
        beneficiaryPromoterUserId,
        Dimension.AVAILABLE,
        expected.available(),
        actual.available());
    addDifference(
        differences,
        LedgerKind.COMMISSION,
        beneficiaryPromoterUserId,
        Dimension.WITHDRAWING,
        expected.withdrawing(),
        actual.withdrawing());
    addDifference(
        differences,
        LedgerKind.COMMISSION,
        beneficiaryPromoterUserId,
        Dimension.WITHDRAWN,
        expected.withdrawn(),
        actual.withdrawn());
    addDifference(
        differences,
        LedgerKind.COMMISSION,
        beneficiaryPromoterUserId,
        Dimension.CASH_DEBT,
        expected.cashDebt(),
        actual.cashDebt());
    return new Preview<>(
        LedgerKind.COMMISSION, beneficiaryPromoterUserId, immutableEntries, differences, false);
  }

  public static Preview<ReferralPointEntitlement> previewPointReward(
      String beneficiaryUserId,
      List<ReferralPointEntitlement> entries,
      PointRewardSnapshot actual) {
    List<ReferralPointEntitlement> immutableEntries =
        List.copyOf(Objects.requireNonNull(entries, "entries"));
    PointRewardSnapshot expected =
        PointRewardAccount.rehydrate(beneficiaryUserId, immutableEntries).currentSnapshot();
    Objects.requireNonNull(actual, "actual");

    List<Difference> differences = new ArrayList<>();
    addDifference(
        differences,
        LedgerKind.POINT_REWARD,
        beneficiaryUserId,
        Dimension.CUMULATIVE_GRANTED,
        expected.cumulativeGranted(),
        actual.cumulativeGranted());
    addDifference(
        differences,
        LedgerKind.POINT_REWARD,
        beneficiaryUserId,
        Dimension.CUMULATIVE_REVERSED,
        expected.cumulativeReversed(),
        actual.cumulativeReversed());
    addDifference(
        differences,
        LedgerKind.POINT_REWARD,
        beneficiaryUserId,
        Dimension.POINT_DEBT,
        expected.pointDebt(),
        actual.pointDebt());
    return new Preview<>(
        LedgerKind.POINT_REWARD, beneficiaryUserId, immutableEntries, differences, false);
  }

  private static void addDifference(
      List<Difference> differences,
      LedgerKind ledgerKind,
      String subjectId,
      Dimension dimension,
      Decimal4 expected,
      Decimal4 actual) {
    if (!expected.equals(actual)) {
      differences.add(
          new Difference(
              ledgerKind, subjectId, dimension, expected, actual, expected.subtract(actual)));
    }
  }

  public enum LedgerKind {
    COMMISSION,
    POINT_REWARD
  }

  public enum Dimension {
    FROZEN,
    AVAILABLE,
    WITHDRAWING,
    WITHDRAWN,
    CASH_DEBT,
    CUMULATIVE_GRANTED,
    CUMULATIVE_REVERSED,
    POINT_DEBT
  }

  public record Difference(
      LedgerKind ledgerKind,
      String subjectId,
      Dimension dimension,
      Decimal4 expected4,
      Decimal4 actual4,
      Decimal4 delta4) {

    public Difference {
      Objects.requireNonNull(ledgerKind, "ledgerKind");
      if (subjectId == null || subjectId.isBlank()) {
        throw new IllegalArgumentException("subjectId must not be blank");
      }
      Objects.requireNonNull(dimension, "dimension");
      Objects.requireNonNull(expected4, "expected4");
      Objects.requireNonNull(actual4, "actual4");
      Objects.requireNonNull(delta4, "delta4");
      if (!delta4.equals(expected4.subtract(actual4))) {
        throw new IllegalArgumentException("delta4 must equal expected4 minus actual4");
      }
    }
  }

  public record Preview<E>(
      LedgerKind ledgerKind,
      String subjectId,
      List<E> inputEntries,
      List<Difference> differences,
      boolean executable) {

    public Preview {
      Objects.requireNonNull(ledgerKind, "ledgerKind");
      if (subjectId == null || subjectId.isBlank()) {
        throw new IllegalArgumentException("subjectId must not be blank");
      }
      inputEntries = List.copyOf(Objects.requireNonNull(inputEntries, "inputEntries"));
      differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
      if (executable) {
        throw new IllegalArgumentException("reconciliation preview is never executable");
      }
      if (differences.stream()
          .anyMatch(
              difference ->
                  difference.ledgerKind() != ledgerKind
                      || !difference.subjectId().equals(subjectId))) {
        throw new IllegalArgumentException("difference does not belong to this preview");
      }
    }
  }
}
