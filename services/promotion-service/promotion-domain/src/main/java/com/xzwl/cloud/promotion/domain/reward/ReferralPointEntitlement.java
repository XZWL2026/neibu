package com.xzwl.cloud.promotion.domain.reward;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.Objects;

/** One immutable point reward entitlement fact. */
public record ReferralPointEntitlement(
    long sequenceNo,
    String beneficiaryUserId,
    PointRewardActionType actionType,
    Decimal4 points4,
    PointRewardSnapshot before,
    PointRewardSnapshot after,
    String sourceEventId,
    RewardSourceSnapshot sourceSnapshot,
    Decimal4 recoveredNow4,
    Instant createdAt,
    PointRewardReason reason) {

  public ReferralPointEntitlement {
    if (sequenceNo < 1) {
      throw new IllegalArgumentException("sequenceNo must be positive");
    }
    beneficiaryUserId = PointRewardAmounts.requireId(beneficiaryUserId, "beneficiaryUserId");
    actionType = Objects.requireNonNull(actionType, "actionType");
    points4 = PointRewardAmounts.requirePositive(points4, "points4");
    before = Objects.requireNonNull(before, "before");
    after = Objects.requireNonNull(after, "after");
    sourceEventId = PointRewardAmounts.requireId(sourceEventId, "sourceEventId");
    sourceSnapshot = Objects.requireNonNull(sourceSnapshot, "sourceSnapshot");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    reason = Objects.requireNonNull(reason, "reason");

    if (actionType == PointRewardActionType.POINT_REWARD_REVERSED) {
      PointRewardAmounts.requireNonNegative(recoveredNow4, "recoveredNow4");
      if (PointRewardAmounts.compare(recoveredNow4, points4) > 0) {
        throw new IllegalArgumentException("recoveredNow4 cannot exceed reversed points");
      }
    } else if (recoveredNow4 != null) {
      throw new IllegalArgumentException("recoveredNow4 is only allowed for reward reversals");
    }

    validateReason(actionType, reason);
    validateTransition(actionType, points4, before, after);
  }

  public ReferralPointEntitlement(
      long sequenceNo,
      String beneficiaryUserId,
      PointRewardActionType actionType,
      Decimal4 points4,
      PointRewardSnapshot before,
      PointRewardSnapshot after,
      String sourceEventId,
      String rewardEntitlementId,
      String sourceSettlementId,
      String sourceAllocationId,
      String sourceFundingLotId,
      String sourceWalletOwnerUserId,
      long referralBindingVersion,
      CashTier cashTier,
      Decimal4 recoveredNow4,
      Instant createdAt,
      PointRewardReason reason) {
    this(
        sequenceNo,
        beneficiaryUserId,
        actionType,
        points4,
        before,
        after,
        sourceEventId,
        new RewardSourceSnapshot(
            rewardEntitlementId,
            sourceSettlementId,
            sourceAllocationId,
            sourceFundingLotId,
            sourceWalletOwnerUserId,
            referralBindingVersion,
            cashTier),
        recoveredNow4,
        createdAt,
        reason);
  }

  public String rewardEntitlementId() {
    return sourceSnapshot.rewardEntitlementId();
  }

  public String sourceSettlementId() {
    return sourceSnapshot.sourceSettlementId();
  }

  public String sourceAllocationId() {
    return sourceSnapshot.sourceAllocationId();
  }

  public String sourceFundingLotId() {
    return sourceSnapshot.sourceFundingLotId();
  }

  public String sourceWalletOwnerUserId() {
    return sourceSnapshot.sourceWalletOwnerUserId();
  }

  public long referralBindingVersion() {
    return sourceSnapshot.referralBindingVersion();
  }

  public CashTier cashTier() {
    return sourceSnapshot.cashTier();
  }

  private static void validateReason(
      PointRewardActionType actionType, PointRewardReason reason) {
    boolean valid =
        switch (actionType) {
          case POINT_REWARD_GRANTED -> reason == PointRewardReason.TASK_SUCCESS_REWARD_GRANTED;
          case POINT_REWARD_REVERSED -> reason == PointRewardReason.TASK_SUCCESS_REWARD_REVERSED;
          case POINT_DEBT_CREATED -> reason == PointRewardReason.REWARD_REVERSAL_SHORTFALL;
          case POINT_DEBT_OFFSET -> reason == PointRewardReason.AUTOMATIC_POINT_DEBT_OFFSET;
        };
    if (!valid) {
      throw new IllegalArgumentException("reason is not allowed for actionType");
    }
  }

  private static void validateTransition(
      PointRewardActionType actionType,
      Decimal4 points,
      PointRewardSnapshot before,
      PointRewardSnapshot after) {
    PointRewardSnapshot expected =
        switch (actionType) {
          case POINT_REWARD_GRANTED -> before.granted(points);
          case POINT_REWARD_REVERSED -> before.reversed(points);
          case POINT_DEBT_CREATED -> before.debtCreated(points);
          case POINT_DEBT_OFFSET -> before.debtOffset(points);
        };
    if (!expected.equals(after)) {
      throw new IllegalArgumentException("before/after transition is inconsistent with action and points");
    }
  }

  String idempotencyKey() {
    return sourceEventId + '\u0000' + actionType + '\u0000' + beneficiaryUserId;
  }

  boolean hasSameSemantics(
      Decimal4 expectedPoints,
      RewardSourceSnapshot expectedSource,
      Decimal4 expectedRecoveredNow,
      Instant expectedCreatedAt,
      PointRewardReason expectedReason) {
    return points4.equals(expectedPoints)
        && sourceSnapshot.equals(expectedSource)
        && Objects.equals(recoveredNow4, expectedRecoveredNow)
        && createdAt.equals(expectedCreatedAt)
        && reason == expectedReason;
  }

  @Override
  public String toString() {
    return "ReferralPointEntitlement[sequenceNo=%d, actionType=%s, points4=%s, before=%s, after=%s, cashTier=%s, createdAt=%s, reason=%s]"
        .formatted(sequenceNo, actionType, points4, before, after, cashTier(), createdAt, reason);
  }
}
