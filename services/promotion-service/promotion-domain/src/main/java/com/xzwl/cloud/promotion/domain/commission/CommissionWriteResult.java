package com.xzwl.cloud.promotion.domain.commission;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.util.List;
import java.util.Objects;

public final class CommissionWriteResult {
  private final List<CommissionEntry> entries;
  private final CommissionBalanceSnapshot snapshot;

  private CommissionWriteResult(
      List<CommissionEntry> entries, CommissionBalanceSnapshot snapshot) {
    this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    if (this.entries.isEmpty() || this.entries.size() > 2) {
      throw new IllegalArgumentException("write result requires one primary and at most one follow-up fact");
    }
    validateShape(this.entries);
    if (!this.entries.getLast().after().equals(snapshot)) {
      throw new IllegalArgumentException("snapshot must equal the last entry after balance");
    }
  }

  private static void validateShape(List<CommissionEntry> facts) {
    CommissionEntry primary = facts.getFirst();
    CommissionActionType followUpAction;
    Decimal4 followUpAmount;
    CommissionEntryReason followUpReason;
    switch (primary.actionType()) {
      case COMMISSION_RELEASED -> {
        followUpAction = CommissionActionType.CASH_DEBT_OFFSET;
        followUpAmount = CommissionAmounts.min(primary.amount4(), primary.before().cashDebt());
        followUpReason = CommissionEntryReason.AUTOMATIC_CASH_DEBT_OFFSET;
      }
      case COMMISSION_RELEASE_REVERSED -> {
        followUpAction = CommissionActionType.CASH_DEBT_CREATED;
        Decimal4 recovered = CommissionAmounts.min(primary.amount4(), primary.before().available());
        followUpAmount = primary.amount4().subtract(recovered);
        followUpReason = CommissionEntryReason.RELEASE_REVERSAL_SHORTFALL;
      }
      case WITHDRAWAL_REJECTED -> {
        followUpAction = CommissionActionType.WITHDRAWAL_REJECTION_DEBT_OFFSET;
        followUpAmount = CommissionAmounts.min(primary.amount4(), primary.before().cashDebt());
        followUpReason = CommissionEntryReason.WITHDRAWAL_REJECTION_CASH_DEBT_OFFSET;
      }
      default -> throw new IllegalArgumentException("write result primary action is not supported");
    }

    boolean requiresFollowUp = CommissionAmounts.compare(followUpAmount, Decimal4.ZERO) > 0;
    if (facts.size() != (requiresFollowUp ? 2 : 1)) {
      throw new IllegalArgumentException("write result follow-up facts do not match the primary fact");
    }
    if (!requiresFollowUp) {
      return;
    }

    CommissionEntry followUp = facts.getLast();
    if (followUp.sequenceNo() != primary.sequenceNo() + 1
        || followUp.actionType() != followUpAction
        || !followUp.amount4().equals(followUpAmount)
        || !followUp.before().equals(primary.after())
        || !followUp.beneficiaryPromoterUserId().equals(primary.beneficiaryPromoterUserId())
        || !followUp.sourceEventId().equals(primary.sourceEventId())
        || !followUp.createdAt().equals(primary.createdAt())
        || followUp.reason() != followUpReason
        || !Objects.equals(followUp.orderId(), primary.orderId())
        || !Objects.equals(followUp.fundingLotId(), primary.fundingLotId())
        || !Objects.equals(followUp.allocationId(), primary.allocationId())
        || !Objects.equals(followUp.withdrawalRequestId(), primary.withdrawalRequestId())
        || !Objects.equals(followUp.referralBindingVersion(), primary.referralBindingVersion())
        || followUp.cashTier() != primary.cashTier()) {
      throw new IllegalArgumentException("write result follow-up fact does not match the primary fact");
    }
  }

  static CommissionWriteResult fromLedgerFacts(
      List<CommissionEntry> entries, CommissionBalanceSnapshot snapshot) {
    return new CommissionWriteResult(entries, snapshot);
  }

  public List<CommissionEntry> entries() {
    return entries;
  }

  public CommissionBalanceSnapshot snapshot() {
    return snapshot;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof CommissionWriteResult that)) {
      return false;
    }
    return entries.equals(that.entries) && snapshot.equals(that.snapshot);
  }

  @Override
  public int hashCode() {
    return Objects.hash(entries, snapshot);
  }

  @Override
  public String toString() {
    return "CommissionWriteResult[entryCount=" + entries.size() + ", snapshot=" + snapshot + "]";
  }
}
