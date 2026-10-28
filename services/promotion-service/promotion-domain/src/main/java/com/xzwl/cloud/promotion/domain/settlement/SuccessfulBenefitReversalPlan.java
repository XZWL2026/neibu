package com.xzwl.cloud.promotion.domain.settlement;

import com.xzwl.cloud.promotion.domain.identity.PromotionIdentifiers;
import java.util.Objects;
import java.util.Optional;

/** Immutable exact reversal of the actions persisted for one successful allocation. */
public record SuccessfulBenefitReversalPlan(
    String sourceSettlementId,
    String sourceAllocationId,
    String sourceFundingLotId,
    String sourceWalletOwnerUserId,
    long referralBindingVersion,
    Optional<PointRewardReversalAction> pointRewardReversal,
    Optional<CashReleaseReversalAction> cashReleaseReversal) {

  public SuccessfulBenefitReversalPlan {
    sourceSettlementId = PromotionIdentifiers.requireId(sourceSettlementId, "sourceSettlementId");
    sourceAllocationId = PromotionIdentifiers.requireId(sourceAllocationId, "sourceAllocationId");
    sourceFundingLotId = PromotionIdentifiers.requireId(sourceFundingLotId, "sourceFundingLotId");
    sourceWalletOwnerUserId =
        PromotionIdentifiers.requireId(sourceWalletOwnerUserId, "sourceWalletOwnerUserId");
    if (referralBindingVersion < 1) {
      throw new IllegalArgumentException("referralBindingVersion must be positive");
    }
    pointRewardReversal =
        Objects.requireNonNull(pointRewardReversal, "pointRewardReversal");
    cashReleaseReversal =
        Objects.requireNonNull(cashReleaseReversal, "cashReleaseReversal");
    if (pointRewardReversal.isPresent()) {
      PointRewardReversalAction action = pointRewardReversal.orElseThrow();
      requireMatchingSources(
          sourceSettlementId,
          sourceAllocationId,
          sourceFundingLotId,
          sourceWalletOwnerUserId,
          referralBindingVersion,
          action.sourceSettlementId(),
          action.sourceAllocationId(),
          action.sourceFundingLotId(),
          action.sourceWalletOwnerUserId(),
          action.referralBindingVersion());
    }
    if (cashReleaseReversal.isPresent()) {
      CashReleaseReversalAction action = cashReleaseReversal.orElseThrow();
      requireMatchingSources(
          sourceSettlementId,
          sourceAllocationId,
          sourceFundingLotId,
          sourceWalletOwnerUserId,
          referralBindingVersion,
          action.sourceSettlementId(),
          action.sourceAllocationId(),
          action.sourceFundingLotId(),
          action.sourceWalletOwnerUserId(),
          action.referralBindingVersion());
    }
  }

  public static SuccessfulBenefitReversalPlan from(
      SuccessfulBenefitResult successfulBenefitResult) {
    Objects.requireNonNull(successfulBenefitResult, "successfulBenefitResult");
    return new SuccessfulBenefitReversalPlan(
        successfulBenefitResult.sourceSettlementId(),
        successfulBenefitResult.sourceAllocationId(),
        successfulBenefitResult.sourceFundingLotId(),
        successfulBenefitResult.sourceWalletOwnerUserId(),
        successfulBenefitResult.referralBindingVersion(),
        successfulBenefitResult.pointReward().map(action ->
            new PointRewardReversalAction(
                successfulBenefitResult.sourceSettlementId(),
                successfulBenefitResult.sourceAllocationId(),
                successfulBenefitResult.sourceFundingLotId(),
                successfulBenefitResult.sourceWalletOwnerUserId(),
                successfulBenefitResult.referralBindingVersion(),
                action.beneficiaryUserId(),
                action.points4())),
        successfulBenefitResult.cashRelease().map(action ->
            new CashReleaseReversalAction(
                successfulBenefitResult.sourceSettlementId(),
                successfulBenefitResult.sourceAllocationId(),
                successfulBenefitResult.sourceFundingLotId(),
                successfulBenefitResult.sourceWalletOwnerUserId(),
                successfulBenefitResult.referralBindingVersion(),
                action.beneficiaryPromoterUserId(),
                action.cashTier(),
                action.amount4())));
  }

  private static void requireMatchingSources(
      String planSettlementId,
      String planAllocationId,
      String planFundingLotId,
      String planWalletOwnerUserId,
      long planBindingVersion,
      String actionSettlementId,
      String actionAllocationId,
      String actionFundingLotId,
      String actionWalletOwnerUserId,
      long actionBindingVersion) {
    if (!planSettlementId.equals(actionSettlementId)
        || !planAllocationId.equals(actionAllocationId)
        || !planFundingLotId.equals(actionFundingLotId)
        || !planWalletOwnerUserId.equals(actionWalletOwnerUserId)
        || planBindingVersion != actionBindingVersion) {
      throw new IllegalArgumentException("reversal action source does not match reversal plan");
    }
  }
}
