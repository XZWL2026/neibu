package com.xzwl.cloud.promotion.domain.commission;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single-promoter cash commission ledger and projection.
 *
 * <p>The in-memory monitor provides single-instance linearization only. A later repository boundary must
 * still use aggregate-version compare-and-swap.
 */
public final class CommissionAccount {

  private final String beneficiaryPromoterUserId;
  private List<CommissionEntry> ledger = new ArrayList<>();
  private Map<String, CommissionEntry> idempotencyIndex = new HashMap<>();
  private Map<String, LotPosition> lots = new HashMap<>();
  private Map<String, AllocationPosition> allocations = new HashMap<>();
  private Map<String, WithdrawalPosition> withdrawals = new HashMap<>();
  private CommissionBalanceSnapshot currentSnapshot = CommissionBalanceSnapshot.zero();
  private PendingFollowUp pendingFollowUp;

  private CommissionAccount(String beneficiaryPromoterUserId) {
    this.beneficiaryPromoterUserId =
        CommissionAmounts.requireId(beneficiaryPromoterUserId, "beneficiaryPromoterUserId");
  }

  public static CommissionAccount create(String beneficiaryPromoterUserId) {
    return new CommissionAccount(beneficiaryPromoterUserId);
  }

  public static CommissionAccount rehydrate(
      String beneficiaryPromoterUserId, List<CommissionEntry> entries) {
    CommissionAccount account = new CommissionAccount(beneficiaryPromoterUserId);
    List<CommissionEntry> immutableEntries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    long expectedSequence = 1;
    for (CommissionEntry entry : immutableEntries) {
      Objects.requireNonNull(entry, "entry");
      if (entry.sequenceNo() != expectedSequence) {
        throw new IllegalArgumentException("ledger sequence must be strictly continuous");
      }
      if (!account.beneficiaryPromoterUserId.equals(entry.beneficiaryPromoterUserId())) {
        throw new IllegalArgumentException("ledger beneficiary does not match account beneficiary");
      }
      if (!account.currentSnapshot.equals(entry.before())) {
        throw new IllegalArgumentException("ledger before/after chain is broken");
      }
      if (account.idempotencyIndex.containsKey(entry.idempotencyKey())) {
        throw new IllegalArgumentException("ledger contains a duplicate idempotency key");
      }
      account.applyHistorical(entry);
      account.ledger.add(entry);
      account.idempotencyIndex.put(entry.idempotencyKey(), entry);
      account.currentSnapshot = entry.after();
      expectedSequence++;
    }
    if (account.pendingFollowUp != null) {
      throw new IllegalArgumentException("ledger ends before a required compound action follow-up");
    }
    return account;
  }

  public synchronized CommissionBalanceSnapshot currentSnapshot() {
    return currentSnapshot;
  }

  public synchronized List<CommissionEntry> entries() {
    return List.copyOf(ledger);
  }

  public synchronized CommissionEntry freezeCommission(
      String sourceEventId,
      String orderId,
      String fundingLotId,
      Decimal4 amount,
      long referralBindingVersion,
      CashTier cashTier,
      Instant createdAt) {
    validateReferralSource(
        sourceEventId, orderId, fundingLotId, amount, referralBindingVersion, cashTier, createdAt);
    if (cashTier == CashTier.NONE) {
      throw new IllegalArgumentException("cash tier NONE cannot freeze cash commission");
    }
    CommissionEntry replay =
        replayOrNull(
            sourceEventId,
            CommissionActionType.COMMISSION_FROZEN,
            amount,
            orderId,
            fundingLotId,
            null,
            null,
            referralBindingVersion,
            cashTier,
            createdAt,
            CommissionEntryReason.RECHARGE_COMMISSION_FROZEN);
    if (replay != null) {
      return replay;
    }
    if (lots.containsKey(fundingLotId)) {
      throw new IllegalStateException("funding lot already has a freeze fact");
    }

    CommissionEntry entry =
        entry(
            CommissionActionType.COMMISSION_FROZEN,
            amount,
            currentSnapshot.frozenBy(amount),
            sourceEventId,
            orderId,
            fundingLotId,
            null,
            null,
            referralBindingVersion,
            cashTier,
            createdAt,
            CommissionEntryReason.RECHARGE_COMMISSION_FROZEN);
    commit(List.of(entry));
    return entry;
  }

  public synchronized CommissionWriteResult releaseCommission(
      String sourceEventId,
      String orderId,
      String fundingLotId,
      String allocationId,
      Decimal4 amount,
      Instant createdAt) {
    CommissionAmounts.requireId(sourceEventId, "sourceEventId");
    CommissionAmounts.requireId(orderId, "orderId");
    CommissionAmounts.requireId(fundingLotId, "fundingLotId");
    CommissionAmounts.requireId(allocationId, "allocationId");
    CommissionAmounts.requirePositive(amount, "amount");
    Objects.requireNonNull(createdAt, "createdAt");

    CommissionEntry existing = find(sourceEventId, CommissionActionType.COMMISSION_RELEASED);
    if (existing != null) {
      assertSemantics(
          existing,
          amount,
          orderId,
          fundingLotId,
          allocationId,
          null,
          existing.referralBindingVersion(),
          existing.cashTier(),
          createdAt,
          CommissionEntryReason.TASK_COMMISSION_RELEASED);
      return originalCompoundResult(existing, CommissionActionType.CASH_DEBT_OFFSET);
    }

    LotPosition lot = requireLot(orderId, fundingLotId);
    if (allocations.containsKey(allocationId)) {
      throw new IllegalStateException("allocationId already has a release fact");
    }
    if (CommissionAmounts.compare(amount, lot.remainingFrozen) > 0) {
      throw new IllegalStateException("release amount exceeds this funding lot remaining frozen amount");
    }

    List<CommissionEntry> facts = new ArrayList<>();
    CommissionBalanceSnapshot released = currentSnapshot.released(amount);
    CommissionEntry release =
        entry(
            CommissionActionType.COMMISSION_RELEASED,
            amount,
            released,
            sourceEventId,
            orderId,
            fundingLotId,
            allocationId,
            null,
            lot.referralBindingVersion,
            lot.cashTier,
            createdAt,
            CommissionEntryReason.TASK_COMMISSION_RELEASED);
    facts.add(release);

    Decimal4 offset = CommissionAmounts.min(amount, currentSnapshot.cashDebt());
    if (CommissionAmounts.compare(offset, Decimal4.ZERO) > 0) {
      facts.add(
          entryWithBefore(
              CommissionActionType.CASH_DEBT_OFFSET,
              offset,
              released,
              released.debtOffset(offset),
              sourceEventId,
              orderId,
              fundingLotId,
              allocationId,
              lot.referralBindingVersion,
              lot.cashTier,
              createdAt,
              CommissionEntryReason.AUTOMATIC_CASH_DEBT_OFFSET));
    }
    commit(facts);
    return CommissionWriteResult.fromLedgerFacts(facts, facts.getLast().after());
  }

  public synchronized CommissionWriteResult reverseRelease(
      String sourceEventId, String allocationId, Decimal4 amount, Instant createdAt) {
    CommissionAmounts.requireId(sourceEventId, "sourceEventId");
    CommissionAmounts.requireId(allocationId, "allocationId");
    CommissionAmounts.requirePositive(amount, "amount");
    Objects.requireNonNull(createdAt, "createdAt");

    CommissionEntry existing = find(sourceEventId, CommissionActionType.COMMISSION_RELEASE_REVERSED);
    if (existing != null) {
      assertSemantics(
          existing,
          amount,
          existing.orderId(),
          existing.fundingLotId(),
          allocationId,
          null,
          existing.referralBindingVersion(),
          existing.cashTier(),
          createdAt,
          CommissionEntryReason.TASK_COMMISSION_RELEASE_REVERSED);
      return originalCompoundResult(existing, CommissionActionType.CASH_DEBT_CREATED);
    }

    AllocationPosition allocation = allocations.get(allocationId);
    if (allocation == null) {
      throw new IllegalStateException("allocationId does not identify a released commission allocation");
    }
    Decimal4 outstanding = allocation.releasedAmount.subtract(allocation.reversedAmount);
    if (CommissionAmounts.compare(amount, outstanding) > 0) {
      throw new IllegalStateException("release reversal exceeds allocation outstanding amount");
    }

    List<CommissionEntry> facts = new ArrayList<>();
    CommissionBalanceSnapshot reversed = currentSnapshot.reverseFromAvailable(amount);
    facts.add(
        entry(
            CommissionActionType.COMMISSION_RELEASE_REVERSED,
            amount,
            reversed,
            sourceEventId,
            allocation.orderId,
            allocation.fundingLotId,
            allocationId,
            null,
            allocation.referralBindingVersion,
            allocation.cashTier,
            createdAt,
            CommissionEntryReason.TASK_COMMISSION_RELEASE_REVERSED));

    Decimal4 debited = CommissionAmounts.min(currentSnapshot.available(), amount);
    Decimal4 shortage = amount.subtract(debited);
    if (CommissionAmounts.compare(shortage, Decimal4.ZERO) > 0) {
      facts.add(
          entryWithBefore(
              CommissionActionType.CASH_DEBT_CREATED,
              shortage,
              reversed,
              reversed.debtCreated(shortage),
              sourceEventId,
              allocation.orderId,
              allocation.fundingLotId,
              allocationId,
              allocation.referralBindingVersion,
              allocation.cashTier,
              createdAt,
              CommissionEntryReason.RELEASE_REVERSAL_SHORTFALL));
    }
    commit(facts);
    return CommissionWriteResult.fromLedgerFacts(facts, facts.getLast().after());
  }

  public synchronized CommissionEntry clawbackUnreleasedRefund(
      String sourceEventId,
      String orderId,
      String fundingLotId,
      Decimal4 amount,
      Instant createdAt) {
    CommissionAmounts.requireId(sourceEventId, "sourceEventId");
    CommissionAmounts.requireId(orderId, "orderId");
    CommissionAmounts.requireId(fundingLotId, "fundingLotId");
    CommissionAmounts.requirePositive(amount, "amount");
    Objects.requireNonNull(createdAt, "createdAt");

    CommissionEntry existing = find(sourceEventId, CommissionActionType.RECHARGE_REFUND_CLAWBACK);
    if (existing != null) {
      assertSemantics(
          existing,
          amount,
          orderId,
          fundingLotId,
          null,
          null,
          existing.referralBindingVersion(),
          existing.cashTier(),
          createdAt,
          CommissionEntryReason.RECHARGE_UNRELEASED_REFUND);
      return existing;
    }

    LotPosition lot = requireLot(orderId, fundingLotId);
    if (CommissionAmounts.compare(amount, lot.remainingFrozen) > 0) {
      throw new IllegalStateException(
          "refund exceeds unreleased frozen amount; released amounts require allocation release reversal");
    }
    CommissionEntry entry =
        entry(
            CommissionActionType.RECHARGE_REFUND_CLAWBACK,
            amount,
            currentSnapshot.clawedBack(amount),
            sourceEventId,
            orderId,
            fundingLotId,
            null,
            null,
            lot.referralBindingVersion,
            lot.cashTier,
            createdAt,
            CommissionEntryReason.RECHARGE_UNRELEASED_REFUND);
    commit(List.of(entry));
    return entry;
  }

  public synchronized CommissionEntry requestWithdrawal(
      String sourceEventId, String withdrawalRequestId, Decimal4 amount, Instant createdAt) {
    validateWithdrawalInput(sourceEventId, withdrawalRequestId, amount, createdAt);
    CommissionEntry replay =
        replayOrNull(
            sourceEventId,
            CommissionActionType.WITHDRAWAL_REQUESTED,
            amount,
            null,
            null,
            null,
            withdrawalRequestId,
            null,
            CashTier.NONE,
            createdAt,
            CommissionEntryReason.WITHDRAWAL_SUBMITTED);
    if (replay != null) {
      return replay;
    }
    if (withdrawals.containsKey(withdrawalRequestId)) {
      throw new IllegalStateException("withdrawalRequestId already exists");
    }
    if (CommissionAmounts.compare(currentSnapshot.cashDebt(), Decimal4.ZERO) > 0) {
      throw new IllegalStateException("cash debt must be cleared before withdrawal");
    }
    if (CommissionAmounts.compare(amount, currentSnapshot.available()) > 0) {
      throw new IllegalStateException("withdrawal amount exceeds available balance");
    }
    CommissionEntry entry =
        entry(
            CommissionActionType.WITHDRAWAL_REQUESTED,
            amount,
            currentSnapshot.withdrawalRequested(amount),
            sourceEventId,
            null,
            null,
            null,
            withdrawalRequestId,
            null,
            CashTier.NONE,
            createdAt,
            CommissionEntryReason.WITHDRAWAL_SUBMITTED);
    commit(List.of(entry));
    return entry;
  }

  public synchronized CommissionWriteResult rejectWithdrawal(
      String sourceEventId,
      String withdrawalRequestId,
      CommissionEntryReason reason,
      Instant createdAt) {
    List<CommissionEntry> facts =
        completeWithdrawalFacts(
            sourceEventId,
            withdrawalRequestId,
            reason,
            createdAt,
            CommissionActionType.WITHDRAWAL_REJECTED);
    return CommissionWriteResult.fromLedgerFacts(facts, facts.getLast().after());
  }

  public synchronized CommissionEntry markWithdrawalPaid(
      String sourceEventId,
      String withdrawalRequestId,
      CommissionEntryReason reason,
      Instant createdAt) {
    return completeWithdrawalFacts(
            sourceEventId,
            withdrawalRequestId,
            reason,
            createdAt,
            CommissionActionType.WITHDRAWAL_PAID)
        .getFirst();
  }

  private List<CommissionEntry> completeWithdrawalFacts(
      String sourceEventId,
      String withdrawalRequestId,
      CommissionEntryReason reason,
      Instant createdAt,
      CommissionActionType actionType) {
    CommissionAmounts.requireId(sourceEventId, "sourceEventId");
    CommissionAmounts.requireId(withdrawalRequestId, "withdrawalRequestId");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(createdAt, "createdAt");

    CommissionEntry existing = find(sourceEventId, actionType);
    if (existing != null) {
      assertSemantics(
          existing,
          existing.amount4(),
          null,
          null,
          null,
          withdrawalRequestId,
          null,
          CashTier.NONE,
          createdAt,
          reason);
      if (actionType == CommissionActionType.WITHDRAWAL_REJECTED) {
        return originalCompoundResult(
                existing, CommissionActionType.WITHDRAWAL_REJECTION_DEBT_OFFSET)
            .entries();
      }
      return List.of(existing);
    }
    WithdrawalPosition withdrawal = withdrawals.get(withdrawalRequestId);
    if (withdrawal == null) {
      throw new IllegalStateException("withdrawalRequestId does not exist");
    }
    if (withdrawal.status != WithdrawalStatus.REQUESTED) {
      throw new IllegalStateException("withdrawal request is not awaiting a decision");
    }
    CommissionBalanceSnapshot after =
        actionType == CommissionActionType.WITHDRAWAL_REJECTED
            ? currentSnapshot.withdrawalRejected(withdrawal.amount)
            : currentSnapshot.withdrawalPaid(withdrawal.amount);
    CommissionEntry entry =
        entry(
            actionType,
            withdrawal.amount,
            after,
            sourceEventId,
            null,
            null,
            null,
            withdrawalRequestId,
            null,
            CashTier.NONE,
            createdAt,
            reason);
    List<CommissionEntry> facts = new ArrayList<>();
    facts.add(entry);
    if (actionType == CommissionActionType.WITHDRAWAL_REJECTED) {
      Decimal4 offset = CommissionAmounts.min(withdrawal.amount, currentSnapshot.cashDebt());
      if (CommissionAmounts.compare(offset, Decimal4.ZERO) > 0) {
        facts.add(
            withdrawalOffsetEntryWithBefore(
                offset,
                after,
                after.debtOffset(offset),
                sourceEventId,
                withdrawalRequestId,
                createdAt));
      }
    }
    commit(facts);
    return List.copyOf(facts);
  }

  private void commit(List<CommissionEntry> facts) {
    List<CommissionEntry> combined = new ArrayList<>(ledger);
    combined.addAll(facts);
    CommissionAccount validated = rehydrate(beneficiaryPromoterUserId, combined);
    ledger = validated.ledger;
    idempotencyIndex = validated.idempotencyIndex;
    lots = validated.lots;
    allocations = validated.allocations;
    withdrawals = validated.withdrawals;
    currentSnapshot = validated.currentSnapshot;
    pendingFollowUp = null;
  }

  private void applyHistorical(CommissionEntry entry) {
    enforcePendingFollowUp(entry);
    switch (entry.actionType()) {
      case COMMISSION_FROZEN -> applyFreeze(entry);
      case COMMISSION_RELEASED -> applyRelease(entry);
      case COMMISSION_RELEASE_REVERSED -> applyReversal(entry);
      case RECHARGE_REFUND_CLAWBACK -> applyClawback(entry);
      case CASH_DEBT_CREATED, CASH_DEBT_OFFSET, WITHDRAWAL_REJECTION_DEBT_OFFSET -> {
        // The required relationship to the immediately preceding primary fact was checked above.
      }
      case WITHDRAWAL_REQUESTED -> applyWithdrawalRequest(entry);
      case WITHDRAWAL_REJECTED -> applyWithdrawalCompletion(entry, WithdrawalStatus.REJECTED);
      case WITHDRAWAL_PAID -> applyWithdrawalCompletion(entry, WithdrawalStatus.PAID);
    }
  }

  private void enforcePendingFollowUp(CommissionEntry entry) {
    if (pendingFollowUp == null) {
      if (entry.actionType() == CommissionActionType.CASH_DEBT_CREATED
          || entry.actionType() == CommissionActionType.CASH_DEBT_OFFSET
          || entry.actionType() == CommissionActionType.WITHDRAWAL_REJECTION_DEBT_OFFSET) {
        throw new IllegalArgumentException("cash debt fact is not attached to its primary ledger action");
      }
      return;
    }
    PendingFollowUp expected = pendingFollowUp;
    pendingFollowUp = null;
    if (entry.actionType() != expected.actionType
        || !entry.sourceEventId().equals(expected.sourceEventId)
        || !entry.amount4().equals(expected.amount)
        || !Objects.equals(entry.orderId(), expected.orderId)
        || !Objects.equals(entry.fundingLotId(), expected.fundingLotId)
        || !Objects.equals(entry.allocationId(), expected.allocationId)
        || !Objects.equals(entry.withdrawalRequestId(), expected.withdrawalRequestId)
        || !Objects.equals(entry.referralBindingVersion(), expected.referralBindingVersion)
        || entry.cashTier() != expected.cashTier
        || !entry.createdAt().equals(expected.createdAt)) {
      throw new IllegalArgumentException("required compound ledger action follow-up is missing or invalid");
    }
  }

  private void applyFreeze(CommissionEntry entry) {
    if (lots.putIfAbsent(
            entry.fundingLotId(),
            new LotPosition(
                entry.orderId(),
                entry.fundingLotId(),
                entry.amount4(),
                entry.referralBindingVersion(),
                entry.cashTier()))
        != null) {
      throw new IllegalArgumentException("ledger freezes the same funding lot more than once");
    }
  }

  private void applyRelease(CommissionEntry entry) {
    LotPosition lot = requireHistoricalLot(entry);
    if (allocations.containsKey(entry.allocationId())) {
      throw new IllegalArgumentException("ledger releases the same allocation more than once");
    }
    if (CommissionAmounts.compare(entry.amount4(), lot.remainingFrozen) > 0) {
      throw new IllegalArgumentException("ledger release exceeds funding lot frozen position");
    }
    lot.remainingFrozen = lot.remainingFrozen.subtract(entry.amount4());
    allocations.put(
        entry.allocationId(),
        new AllocationPosition(
            entry.orderId(),
            entry.fundingLotId(),
            entry.allocationId(),
            entry.amount4(),
            entry.referralBindingVersion(),
            entry.cashTier()));
    Decimal4 requiredOffset = CommissionAmounts.min(entry.before().cashDebt(), entry.amount4());
    if (CommissionAmounts.compare(requiredOffset, Decimal4.ZERO) > 0) {
      pendingFollowUp =
          new PendingFollowUp(
              CommissionActionType.CASH_DEBT_OFFSET,
              entry.sourceEventId(),
              entry.orderId(),
              entry.fundingLotId(),
              entry.allocationId(),
              null,
              entry.referralBindingVersion(),
              entry.cashTier(),
              entry.createdAt(),
              requiredOffset);
    }
  }

  private void applyReversal(CommissionEntry entry) {
    AllocationPosition allocation = allocations.get(entry.allocationId());
    if (allocation == null || !allocation.matches(entry)) {
      throw new IllegalArgumentException("ledger reversal source allocation is invalid");
    }
    Decimal4 outstanding = allocation.releasedAmount.subtract(allocation.reversedAmount);
    if (CommissionAmounts.compare(entry.amount4(), outstanding) > 0) {
      throw new IllegalArgumentException("ledger reverses more than allocation outstanding amount");
    }
    allocation.reversedAmount = allocation.reversedAmount.add(entry.amount4());
    Decimal4 debited = CommissionAmounts.min(entry.before().available(), entry.amount4());
    Decimal4 shortage = entry.amount4().subtract(debited);
    if (CommissionAmounts.compare(shortage, Decimal4.ZERO) > 0) {
      pendingFollowUp =
          new PendingFollowUp(
              CommissionActionType.CASH_DEBT_CREATED,
              entry.sourceEventId(),
              entry.orderId(),
              entry.fundingLotId(),
              entry.allocationId(),
              null,
              entry.referralBindingVersion(),
              entry.cashTier(),
              entry.createdAt(),
              shortage);
    }
  }

  private void applyClawback(CommissionEntry entry) {
    LotPosition lot = requireHistoricalLot(entry);
    if (CommissionAmounts.compare(entry.amount4(), lot.remainingFrozen) > 0) {
      throw new IllegalArgumentException("ledger refund exceeds funding lot unreleased frozen position");
    }
    lot.remainingFrozen = lot.remainingFrozen.subtract(entry.amount4());
  }

  private void applyWithdrawalRequest(CommissionEntry entry) {
    if (withdrawals.putIfAbsent(
            entry.withdrawalRequestId(),
            new WithdrawalPosition(entry.amount4(), WithdrawalStatus.REQUESTED))
        != null) {
      throw new IllegalArgumentException("ledger requests the same withdrawal more than once");
    }
    if (CommissionAmounts.compare(entry.before().cashDebt(), Decimal4.ZERO) > 0) {
      throw new IllegalArgumentException("ledger withdrawal request is invalid while cash debt exists");
    }
  }

  private void applyWithdrawalCompletion(CommissionEntry entry, WithdrawalStatus status) {
    WithdrawalPosition withdrawal = withdrawals.get(entry.withdrawalRequestId());
    if (withdrawal == null
        || withdrawal.status != WithdrawalStatus.REQUESTED
        || !withdrawal.amount.equals(entry.amount4())) {
      throw new IllegalArgumentException("ledger withdrawal completion has invalid source state");
    }
    withdrawal.status = status;
    if (status == WithdrawalStatus.REJECTED) {
      Decimal4 requiredOffset =
          CommissionAmounts.min(entry.before().cashDebt(), entry.amount4());
      if (CommissionAmounts.compare(requiredOffset, Decimal4.ZERO) > 0) {
        pendingFollowUp =
            new PendingFollowUp(
                CommissionActionType.WITHDRAWAL_REJECTION_DEBT_OFFSET,
                entry.sourceEventId(),
                null,
                null,
                null,
                entry.withdrawalRequestId(),
                null,
                CashTier.NONE,
                entry.createdAt(),
                requiredOffset);
      }
    }
  }

  private LotPosition requireHistoricalLot(CommissionEntry entry) {
    LotPosition lot = lots.get(entry.fundingLotId());
    if (lot == null || !lot.matches(entry)) {
      throw new IllegalArgumentException("ledger funding lot source is invalid");
    }
    return lot;
  }

  private LotPosition requireLot(String orderId, String fundingLotId) {
    LotPosition lot = lots.get(fundingLotId);
    if (lot == null || !lot.orderId.equals(orderId)) {
      throw new IllegalStateException("orderId and fundingLotId do not identify a commission lot");
    }
    return lot;
  }

  private CommissionEntry entry(
      CommissionActionType actionType,
      Decimal4 amount,
      CommissionBalanceSnapshot after,
      String sourceEventId,
      String orderId,
      String fundingLotId,
      String allocationId,
      String withdrawalRequestId,
      Long referralBindingVersion,
      CashTier cashTier,
      Instant createdAt,
      CommissionEntryReason reason) {
    return new CommissionEntry(
        ledger.size() + 1L,
        beneficiaryPromoterUserId,
        actionType,
        amount,
        currentSnapshot,
        after,
        sourceEventId,
        orderId,
        fundingLotId,
        allocationId,
        withdrawalRequestId,
        referralBindingVersion,
        cashTier,
        createdAt,
        reason);
  }

  private CommissionEntry entryWithBefore(
      CommissionActionType actionType,
      Decimal4 amount,
      CommissionBalanceSnapshot before,
      CommissionBalanceSnapshot after,
      String sourceEventId,
      String orderId,
      String fundingLotId,
      String allocationId,
      Long referralBindingVersion,
      CashTier cashTier,
      Instant createdAt,
      CommissionEntryReason reason) {
    return new CommissionEntry(
        ledger.size() + 2L,
        beneficiaryPromoterUserId,
        actionType,
        amount,
        before,
        after,
        sourceEventId,
        orderId,
        fundingLotId,
        allocationId,
        null,
        referralBindingVersion,
        cashTier,
        createdAt,
        reason);
  }

  private CommissionEntry withdrawalOffsetEntryWithBefore(
      Decimal4 amount,
      CommissionBalanceSnapshot before,
      CommissionBalanceSnapshot after,
      String sourceEventId,
      String withdrawalRequestId,
      Instant createdAt) {
    return new CommissionEntry(
        ledger.size() + 2L,
        beneficiaryPromoterUserId,
        CommissionActionType.WITHDRAWAL_REJECTION_DEBT_OFFSET,
        amount,
        before,
        after,
        sourceEventId,
        null,
        null,
        null,
        withdrawalRequestId,
        null,
        CashTier.NONE,
        createdAt,
        CommissionEntryReason.WITHDRAWAL_REJECTION_CASH_DEBT_OFFSET);
  }

  private CommissionWriteResult originalCompoundResult(
      CommissionEntry primary, CommissionActionType possibleFollowUp) {
    List<CommissionEntry> result = new ArrayList<>();
    result.add(primary);
    CommissionEntry followUp = find(primary.sourceEventId(), possibleFollowUp);
    if (followUp != null) {
      result.add(followUp);
    }
    return CommissionWriteResult.fromLedgerFacts(result, result.getLast().after());
  }

  private CommissionEntry replayOrNull(
      String sourceEventId,
      CommissionActionType actionType,
      Decimal4 amount,
      String orderId,
      String fundingLotId,
      String allocationId,
      String withdrawalRequestId,
      Long referralBindingVersion,
      CashTier cashTier,
      Instant createdAt,
      CommissionEntryReason reason) {
    CommissionEntry existing = find(sourceEventId, actionType);
    if (existing == null) {
      return null;
    }
    assertSemantics(
        existing,
        amount,
        orderId,
        fundingLotId,
        allocationId,
        withdrawalRequestId,
        referralBindingVersion,
        cashTier,
        createdAt,
        reason);
    return existing;
  }

  private void assertSemantics(
      CommissionEntry existing,
      Decimal4 amount,
      String orderId,
      String fundingLotId,
      String allocationId,
      String withdrawalRequestId,
      Long referralBindingVersion,
      CashTier cashTier,
      Instant createdAt,
      CommissionEntryReason reason) {
    if (!existing.hasSameSemantics(
        amount,
        orderId,
        fundingLotId,
        allocationId,
        withdrawalRequestId,
        referralBindingVersion,
        cashTier,
        createdAt,
        reason)) {
      throw new IllegalStateException("idempotency key was replayed with different semantics");
    }
  }

  private CommissionEntry find(String sourceEventId, CommissionActionType actionType) {
    return idempotencyIndex.get(
        sourceEventId + '\u0000' + actionType + '\u0000' + beneficiaryPromoterUserId);
  }

  private void validateReferralSource(
      String sourceEventId,
      String orderId,
      String fundingLotId,
      Decimal4 amount,
      long referralBindingVersion,
      CashTier cashTier,
      Instant createdAt) {
    CommissionAmounts.requireId(sourceEventId, "sourceEventId");
    CommissionAmounts.requireId(orderId, "orderId");
    CommissionAmounts.requireId(fundingLotId, "fundingLotId");
    CommissionAmounts.requirePositive(amount, "amount");
    if (referralBindingVersion < 1) {
      throw new IllegalArgumentException("referralBindingVersion must be positive");
    }
    Objects.requireNonNull(cashTier, "cashTier");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  private void validateWithdrawalInput(
      String sourceEventId, String withdrawalRequestId, Decimal4 amount, Instant createdAt) {
    CommissionAmounts.requireId(sourceEventId, "sourceEventId");
    CommissionAmounts.requireId(withdrawalRequestId, "withdrawalRequestId");
    CommissionAmounts.requirePositive(amount, "amount");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  private static final class LotPosition {
    private final String orderId;
    private final String fundingLotId;
    private Decimal4 remainingFrozen;
    private final long referralBindingVersion;
    private final CashTier cashTier;

    private LotPosition(
        String orderId,
        String fundingLotId,
        Decimal4 remainingFrozen,
        long referralBindingVersion,
        CashTier cashTier) {
      this.orderId = orderId;
      this.fundingLotId = fundingLotId;
      this.remainingFrozen = remainingFrozen;
      this.referralBindingVersion = referralBindingVersion;
      this.cashTier = cashTier;
    }

    private boolean matches(CommissionEntry entry) {
      return orderId.equals(entry.orderId())
          && fundingLotId.equals(entry.fundingLotId())
          && referralBindingVersion == entry.referralBindingVersion()
          && cashTier == entry.cashTier();
    }
  }

  private static final class AllocationPosition {
    private final String orderId;
    private final String fundingLotId;
    private final String allocationId;
    private final Decimal4 releasedAmount;
    private Decimal4 reversedAmount = Decimal4.ZERO;
    private final long referralBindingVersion;
    private final CashTier cashTier;

    private AllocationPosition(
        String orderId,
        String fundingLotId,
        String allocationId,
        Decimal4 releasedAmount,
        long referralBindingVersion,
        CashTier cashTier) {
      this.orderId = orderId;
      this.fundingLotId = fundingLotId;
      this.allocationId = allocationId;
      this.releasedAmount = releasedAmount;
      this.referralBindingVersion = referralBindingVersion;
      this.cashTier = cashTier;
    }

    private boolean matches(CommissionEntry entry) {
      return orderId.equals(entry.orderId())
          && fundingLotId.equals(entry.fundingLotId())
          && allocationId.equals(entry.allocationId())
          && referralBindingVersion == entry.referralBindingVersion()
          && cashTier == entry.cashTier();
    }
  }

  private static final class WithdrawalPosition {
    private final Decimal4 amount;
    private WithdrawalStatus status;

    private WithdrawalPosition(Decimal4 amount, WithdrawalStatus status) {
      this.amount = amount;
      this.status = status;
    }
  }

  private enum WithdrawalStatus {
    REQUESTED,
    REJECTED,
    PAID
  }

  private record PendingFollowUp(
      CommissionActionType actionType,
      String sourceEventId,
      String orderId,
      String fundingLotId,
      String allocationId,
      String withdrawalRequestId,
      Long referralBindingVersion,
      CashTier cashTier,
      Instant createdAt,
      Decimal4 amount) {}
}
