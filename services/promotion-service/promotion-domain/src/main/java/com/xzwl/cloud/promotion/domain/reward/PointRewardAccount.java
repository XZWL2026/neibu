package com.xzwl.cloud.promotion.domain.reward;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Promotion-side immutable reward entitlement ledger for one direct beneficiary.
 *
 * <p>Public writes are synchronized and linearizable only within this aggregate instance. A later
 * persistence adapter must provide database locking or aggregate-version compare-and-swap across
 * processes. Billing owns real point lots, wallet balances, spending, and the non-benefit-eligible
 * reward lot created from {@link PointRewardWriteResult#netRewardToCredit()}.
 */
public final class PointRewardAccount {

  private final String beneficiaryUserId;
  private List<ReferralPointEntitlement> ledger = new ArrayList<>();
  private Map<String, ReferralPointEntitlement> idempotencyIndex = new HashMap<>();
  private Map<String, EntitlementPosition> positions = new HashMap<>();
  private PointRewardSnapshot currentSnapshot = PointRewardSnapshot.zero();
  private PendingFollowUp pendingFollowUp;

  private PointRewardAccount(String beneficiaryUserId) {
    this.beneficiaryUserId =
        PointRewardAmounts.requireId(beneficiaryUserId, "beneficiaryUserId");
  }

  public static PointRewardAccount create(String beneficiaryUserId) {
    return new PointRewardAccount(beneficiaryUserId);
  }

  public static PointRewardAccount rehydrate(
      String beneficiaryUserId, List<ReferralPointEntitlement> entries) {
    PointRewardAccount account = new PointRewardAccount(beneficiaryUserId);
    List<ReferralPointEntitlement> immutable =
        List.copyOf(Objects.requireNonNull(entries, "entries"));
    long expectedSequence = 1;
    for (ReferralPointEntitlement entry : immutable) {
      Objects.requireNonNull(entry, "entry");
      if (entry.sequenceNo() != expectedSequence) {
        throw new IllegalArgumentException("reward ledger sequence must be strictly continuous");
      }
      if (!account.beneficiaryUserId.equals(entry.beneficiaryUserId())) {
        throw new IllegalArgumentException("reward ledger beneficiary does not match account beneficiary");
      }
      if (!account.currentSnapshot.equals(entry.before())) {
        throw new IllegalArgumentException("reward ledger before/after chain is broken");
      }
      if (account.idempotencyIndex.containsKey(entry.idempotencyKey())) {
        throw new IllegalArgumentException("reward ledger contains a duplicate idempotency key");
      }
      account.applyHistorical(entry);
      account.ledger.add(entry);
      account.idempotencyIndex.put(entry.idempotencyKey(), entry);
      account.currentSnapshot = entry.after();
      expectedSequence++;
    }
    if (account.pendingFollowUp != null) {
      throw new IllegalArgumentException("reward ledger ends before a required compound follow-up");
    }
    return account;
  }

  public synchronized PointRewardSnapshot currentSnapshot() {
    return currentSnapshot;
  }

  public synchronized List<ReferralPointEntitlement> entries() {
    return List.copyOf(ledger);
  }

  public synchronized PointRewardWriteResult grant(
      String sourceEventId,
      String rewardEntitlementId,
      String sourceSettlementId,
      String sourceAllocationId,
      String sourceFundingLotId,
      String sourceWalletOwnerUserId,
      long referralBindingVersion,
      CashTier cashTier,
      Decimal4 points,
      Instant createdAt) {
    validateOperation(sourceEventId, points, createdAt);
    RewardSourceSnapshot source =
        new RewardSourceSnapshot(
            rewardEntitlementId,
            sourceSettlementId,
            sourceAllocationId,
            sourceFundingLotId,
            sourceWalletOwnerUserId,
            referralBindingVersion,
            cashTier);

    ReferralPointEntitlement existing =
        find(sourceEventId, PointRewardActionType.POINT_REWARD_GRANTED);
    if (existing != null) {
      assertSemantics(
          existing,
          points,
          source,
          null,
          createdAt,
          PointRewardReason.TASK_SUCCESS_REWARD_GRANTED);
      return originalCompoundResult(existing, PointRewardActionType.POINT_DEBT_OFFSET);
    }
    if (positions.containsKey(source.rewardEntitlementId())) {
      throw new IllegalStateException("reward entitlement was already granted");
    }

    List<ReferralPointEntitlement> facts = new ArrayList<>();
    PointRewardSnapshot granted = currentSnapshot.granted(points);
    facts.add(
        fact(
            0,
            PointRewardActionType.POINT_REWARD_GRANTED,
            points,
            currentSnapshot,
            granted,
            sourceEventId,
            source,
            null,
            createdAt,
            PointRewardReason.TASK_SUCCESS_REWARD_GRANTED));

    Decimal4 offset = PointRewardAmounts.min(points, currentSnapshot.pointDebt());
    if (PointRewardAmounts.compare(offset, Decimal4.ZERO) > 0) {
      facts.add(
          fact(
              1,
              PointRewardActionType.POINT_DEBT_OFFSET,
              offset,
              granted,
              granted.debtOffset(offset),
              sourceEventId,
              source,
              null,
              createdAt,
              PointRewardReason.AUTOMATIC_POINT_DEBT_OFFSET));
    }
    commit(facts);
    return PointRewardWriteResult.fromLedgerFacts(
        facts, facts.getLast().after(), points.subtract(offset));
  }

  public synchronized PointRewardWriteResult reverse(
      String sourceEventId,
      String rewardEntitlementId,
      String sourceSettlementId,
      String sourceAllocationId,
      String sourceFundingLotId,
      String sourceWalletOwnerUserId,
      long referralBindingVersion,
      CashTier cashTier,
      Decimal4 reverseAmount,
      Decimal4 recoveredNow,
      Instant createdAt) {
    validateOperation(sourceEventId, reverseAmount, createdAt);
    RewardSourceSnapshot source =
        new RewardSourceSnapshot(
            rewardEntitlementId,
            sourceSettlementId,
            sourceAllocationId,
            sourceFundingLotId,
            sourceWalletOwnerUserId,
            referralBindingVersion,
            cashTier);
    PointRewardAmounts.requireNonNegative(recoveredNow, "recoveredNow");
    if (PointRewardAmounts.compare(recoveredNow, reverseAmount) > 0) {
      throw new IllegalArgumentException("recoveredNow cannot exceed reverseAmount");
    }

    ReferralPointEntitlement existing =
        find(sourceEventId, PointRewardActionType.POINT_REWARD_REVERSED);
    if (existing != null) {
      assertSemantics(
          existing,
          reverseAmount,
          source,
          recoveredNow,
          createdAt,
          PointRewardReason.TASK_SUCCESS_REWARD_REVERSED);
      return originalCompoundResult(existing, PointRewardActionType.POINT_DEBT_CREATED);
    }

    EntitlementPosition position = positions.get(source.rewardEntitlementId());
    if (position == null) {
      throw new IllegalStateException("reward entitlement does not identify an existing grant");
    }
    if (!position.source.equals(source)) {
      throw new IllegalStateException("reward reversal source snapshot differs from original entitlement");
    }
    Decimal4 outstanding = position.granted.subtract(position.reversed);
    if (PointRewardAmounts.compare(reverseAmount, outstanding) > 0) {
      throw new IllegalStateException("reward reversal exceeds entitlement outstanding amount");
    }

    List<ReferralPointEntitlement> facts = new ArrayList<>();
    PointRewardSnapshot reversed = currentSnapshot.reversed(reverseAmount);
    facts.add(
        fact(
            0,
            PointRewardActionType.POINT_REWARD_REVERSED,
            reverseAmount,
            currentSnapshot,
            reversed,
            sourceEventId,
            source,
            recoveredNow,
            createdAt,
            PointRewardReason.TASK_SUCCESS_REWARD_REVERSED));
    Decimal4 shortage = reverseAmount.subtract(recoveredNow);
    if (PointRewardAmounts.compare(shortage, Decimal4.ZERO) > 0) {
      facts.add(
          fact(
              1,
              PointRewardActionType.POINT_DEBT_CREATED,
              shortage,
              reversed,
              reversed.debtCreated(shortage),
              sourceEventId,
              source,
              null,
              createdAt,
              PointRewardReason.REWARD_REVERSAL_SHORTFALL));
    }
    commit(facts);
    return PointRewardWriteResult.fromLedgerFacts(
        facts, facts.getLast().after(), Decimal4.ZERO);
  }

  private void commit(List<ReferralPointEntitlement> facts) {
    List<ReferralPointEntitlement> candidateHistory = new ArrayList<>(ledger);
    candidateHistory.addAll(facts);
    PointRewardAccount candidate = rehydrate(beneficiaryUserId, candidateHistory);
    ledger = new ArrayList<>(candidate.ledger);
    idempotencyIndex = new HashMap<>(candidate.idempotencyIndex);
    positions = new HashMap<>(candidate.positions);
    currentSnapshot = candidate.currentSnapshot;
    pendingFollowUp = null;
  }

  private void applyHistorical(ReferralPointEntitlement entry) {
    if (pendingFollowUp != null) {
      pendingFollowUp.requireMatch(entry);
      pendingFollowUp = null;
    } else if (entry.actionType() == PointRewardActionType.POINT_DEBT_CREATED
        || entry.actionType() == PointRewardActionType.POINT_DEBT_OFFSET) {
      throw new IllegalArgumentException("reward ledger contains an orphan debt fact");
    }

    switch (entry.actionType()) {
      case POINT_REWARD_GRANTED -> applyHistoricalGrant(entry);
      case POINT_REWARD_REVERSED -> applyHistoricalReverse(entry);
      case POINT_DEBT_CREATED, POINT_DEBT_OFFSET -> {
        // Their projection transition and compound relationship have already been validated.
      }
    }
  }

  private void applyHistoricalGrant(ReferralPointEntitlement entry) {
    if (positions.containsKey(entry.rewardEntitlementId())) {
      throw new IllegalArgumentException("reward ledger grants a duplicate entitlement");
    }
    positions.put(
        entry.rewardEntitlementId(),
        new EntitlementPosition(entry.points4(), Decimal4.ZERO, entry.sourceSnapshot()));
    Decimal4 expectedOffset = PointRewardAmounts.min(entry.points4(), entry.before().pointDebt());
    if (PointRewardAmounts.compare(expectedOffset, Decimal4.ZERO) > 0) {
      pendingFollowUp =
          PendingFollowUp.from(
              entry,
              PointRewardActionType.POINT_DEBT_OFFSET,
              expectedOffset,
              PointRewardReason.AUTOMATIC_POINT_DEBT_OFFSET);
    }
  }

  private void applyHistoricalReverse(ReferralPointEntitlement entry) {
    EntitlementPosition position = positions.get(entry.rewardEntitlementId());
    if (position == null) {
      throw new IllegalArgumentException("reward reversal references an unknown entitlement");
    }
    if (!position.source.equals(entry.sourceSnapshot())) {
      throw new IllegalArgumentException("reward reversal source snapshot is not the original entitlement");
    }
    Decimal4 outstanding = position.granted.subtract(position.reversed);
    if (PointRewardAmounts.compare(entry.points4(), outstanding) > 0) {
      throw new IllegalArgumentException("reward ledger reverses more than the entitlement grant");
    }
    positions.put(entry.rewardEntitlementId(), position.reversedBy(entry.points4()));
    Decimal4 shortage = entry.points4().subtract(entry.recoveredNow4());
    if (PointRewardAmounts.compare(shortage, Decimal4.ZERO) > 0) {
      pendingFollowUp =
          PendingFollowUp.from(
              entry,
              PointRewardActionType.POINT_DEBT_CREATED,
              shortage,
              PointRewardReason.REWARD_REVERSAL_SHORTFALL);
    }
  }

  private ReferralPointEntitlement find(
      String sourceEventId, PointRewardActionType actionType) {
    PointRewardAmounts.requireId(sourceEventId, "sourceEventId");
    return idempotencyIndex.get(
        sourceEventId + '\u0000' + actionType + '\u0000' + beneficiaryUserId);
  }

  private PointRewardWriteResult originalCompoundResult(
      ReferralPointEntitlement primary, PointRewardActionType possibleFollowUp) {
    List<ReferralPointEntitlement> original = new ArrayList<>();
    original.add(primary);
    int nextIndex = Math.toIntExact(primary.sequenceNo());
    if (nextIndex < ledger.size()) {
      ReferralPointEntitlement next = ledger.get(nextIndex);
      if (next.actionType() == possibleFollowUp
          && next.sourceEventId().equals(primary.sourceEventId())) {
        original.add(next);
      }
    }
    Decimal4 net =
        primary.actionType() == PointRewardActionType.POINT_REWARD_GRANTED
            ? primary.points4().subtract(
                original.size() == 2 ? original.getLast().points4() : Decimal4.ZERO)
            : Decimal4.ZERO;
    return PointRewardWriteResult.fromLedgerFacts(original, original.getLast().after(), net);
  }

  private static void assertSemantics(
      ReferralPointEntitlement existing,
      Decimal4 points,
      RewardSourceSnapshot source,
      Decimal4 recoveredNow,
      Instant createdAt,
      PointRewardReason reason) {
    if (!existing.hasSameSemantics(points, source, recoveredNow, createdAt, reason)) {
      throw new IllegalStateException("idempotency key was already used with different semantics");
    }
  }

  private ReferralPointEntitlement fact(
      int operationIndex,
      PointRewardActionType actionType,
      Decimal4 points,
      PointRewardSnapshot before,
      PointRewardSnapshot after,
      String sourceEventId,
      RewardSourceSnapshot source,
      Decimal4 recoveredNow,
      Instant createdAt,
      PointRewardReason reason) {
    return new ReferralPointEntitlement(
        ledger.size() + operationIndex + 1L,
        beneficiaryUserId,
        actionType,
        points,
        before,
        after,
        sourceEventId,
        source,
        recoveredNow,
        createdAt,
        reason);
  }

  private static void validateOperation(
      String sourceEventId, Decimal4 points, Instant createdAt) {
    PointRewardAmounts.requireId(sourceEventId, "sourceEventId");
    PointRewardAmounts.requirePositive(points, "points");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  private record EntitlementPosition(
      Decimal4 granted, Decimal4 reversed, RewardSourceSnapshot source) {

    EntitlementPosition reversedBy(Decimal4 points) {
      return new EntitlementPosition(granted, reversed.add(points), source);
    }
  }

  private record PendingFollowUp(
      PointRewardActionType actionType,
      Decimal4 points,
      String sourceEventId,
      RewardSourceSnapshot source,
      Instant createdAt,
      PointRewardReason reason) {

    static PendingFollowUp from(
        ReferralPointEntitlement primary,
        PointRewardActionType actionType,
        Decimal4 points,
        PointRewardReason reason) {
      return new PendingFollowUp(
          actionType,
          points,
          primary.sourceEventId(),
          primary.sourceSnapshot(),
          primary.createdAt(),
          reason);
    }

    void requireMatch(ReferralPointEntitlement entry) {
      boolean matches =
          entry.actionType() == actionType
              && entry.points4().equals(points)
              && entry.sourceEventId().equals(sourceEventId)
              && entry.sourceSnapshot().equals(source)
              && entry.createdAt().equals(createdAt)
              && entry.reason() == reason;
      if (!matches) {
        throw new IllegalArgumentException("reward ledger compound follow-up is missing or tampered");
      }
    }
  }
}
