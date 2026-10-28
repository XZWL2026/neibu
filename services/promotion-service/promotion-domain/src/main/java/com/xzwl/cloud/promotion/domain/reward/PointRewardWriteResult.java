package com.xzwl.cloud.promotion.domain.reward;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import java.util.List;
import java.util.Objects;

/**
 * Trusted result of an atomic entitlement write.
 *
 * <p>The net reward is a Billing instruction, not a wallet balance. Only the aggregate's
 * package-private factory can create this value; external callers can inspect but cannot manufacture
 * a Billing credit instruction.
 */
public final class PointRewardWriteResult {

  private final List<ReferralPointEntitlement> entries;
  private final PointRewardSnapshot snapshot;
  private final Decimal4 netRewardToCredit;

  private PointRewardWriteResult(
      List<ReferralPointEntitlement> entries,
      PointRewardSnapshot snapshot,
      Decimal4 netRewardToCredit) {
    this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    if (this.entries.isEmpty()) {
      throw new IllegalArgumentException("write result requires at least one entry");
    }
    if (this.entries.size() > 2) {
      throw new IllegalArgumentException("write result requires exactly one or two compound facts");
    }
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.netRewardToCredit =
        PointRewardAmounts.requireNonNegative(netRewardToCredit, "netRewardToCredit");
    validateShape();
  }

  static PointRewardWriteResult fromLedgerFacts(
      List<ReferralPointEntitlement> entries,
      PointRewardSnapshot snapshot,
      Decimal4 netRewardToCredit) {
    return new PointRewardWriteResult(entries, snapshot, netRewardToCredit);
  }

  public List<ReferralPointEntitlement> entries() {
    return entries;
  }

  public PointRewardSnapshot snapshot() {
    return snapshot;
  }

  public Decimal4 netRewardToCredit() {
    return netRewardToCredit;
  }

  private void validateShape() {
    ReferralPointEntitlement primary = entries.getFirst();
    if (primary.actionType() != PointRewardActionType.POINT_REWARD_GRANTED
        && primary.actionType() != PointRewardActionType.POINT_REWARD_REVERSED) {
      throw new IllegalArgumentException("write result primary fact must be a grant or reversal");
    }

    PointRewardActionType expectedFollowUpAction;
    PointRewardReason expectedFollowUpReason;
    Decimal4 expectedFollowUpPoints;
    if (primary.actionType() == PointRewardActionType.POINT_REWARD_GRANTED) {
      expectedFollowUpAction = PointRewardActionType.POINT_DEBT_OFFSET;
      expectedFollowUpReason = PointRewardReason.AUTOMATIC_POINT_DEBT_OFFSET;
      expectedFollowUpPoints =
          PointRewardAmounts.min(primary.points4(), primary.before().pointDebt());
    } else {
      expectedFollowUpAction = PointRewardActionType.POINT_DEBT_CREATED;
      expectedFollowUpReason = PointRewardReason.REWARD_REVERSAL_SHORTFALL;
      expectedFollowUpPoints = primary.points4().subtract(primary.recoveredNow4());
    }
    boolean followUpRequired =
        PointRewardAmounts.compare(expectedFollowUpPoints, Decimal4.ZERO) > 0;
    if (followUpRequired && entries.size() != 2) {
      throw new IllegalArgumentException("write result is missing its required compound follow-up");
    }
    if (!followUpRequired && entries.size() != 1) {
      throw new IllegalArgumentException("write result has an unexpected compound follow-up");
    }
    if (followUpRequired) {
      validateFollowUp(
          primary,
          entries.get(1),
          expectedFollowUpAction,
          expectedFollowUpReason,
          expectedFollowUpPoints);
    }
    if (!entries.getLast().after().equals(snapshot)) {
      throw new IllegalArgumentException("snapshot must equal the final entry projection");
    }
    Decimal4 expectedNet =
        primary.actionType() == PointRewardActionType.POINT_REWARD_GRANTED
            ? primary.points4().subtract(followUpRequired ? expectedFollowUpPoints : Decimal4.ZERO)
            : Decimal4.ZERO;
    if (!expectedNet.equals(netRewardToCredit)) {
      throw new IllegalArgumentException("netRewardToCredit must be derived from immutable facts");
    }
  }

  private static void validateFollowUp(
      ReferralPointEntitlement primary,
      ReferralPointEntitlement followUp,
      PointRewardActionType expectedAction,
      PointRewardReason expectedReason,
      Decimal4 expectedPoints) {
    boolean valid =
        followUp.sequenceNo() == primary.sequenceNo() + 1
            && followUp.actionType() == expectedAction
            && followUp.points4().equals(expectedPoints)
            && followUp.before().equals(primary.after())
            && followUp.beneficiaryUserId().equals(primary.beneficiaryUserId())
            && followUp.sourceEventId().equals(primary.sourceEventId())
            && followUp.sourceSnapshot().equals(primary.sourceSnapshot())
            && followUp.createdAt().equals(primary.createdAt())
            && followUp.recoveredNow4() == null
            && followUp.reason() == expectedReason;
    if (!valid) {
      throw new IllegalArgumentException("write result compound follow-up is missing or tampered");
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PointRewardWriteResult result)) {
      return false;
    }
    return entries.equals(result.entries)
        && snapshot.equals(result.snapshot)
        && netRewardToCredit.equals(result.netRewardToCredit);
  }

  @Override
  public int hashCode() {
    return Objects.hash(entries, snapshot, netRewardToCredit);
  }

  @Override
  public String toString() {
    return "PointRewardWriteResult[entryCount=%d, snapshot=%s, netRewardToCredit=%s]"
        .formatted(entries.size(), snapshot, netRewardToCredit);
  }
}
