package com.xzwl.cloud.promotion.domain.commission;

import com.xzwl.cloud.promotion.domain.money.Decimal4;
import com.xzwl.cloud.promotion.domain.referral.CashTier;
import java.time.Instant;
import java.util.Objects;

public record CommissionEntry(
    long sequenceNo,
    String beneficiaryPromoterUserId,
    CommissionActionType actionType,
    Decimal4 amount4,
    CommissionBalanceSnapshot before,
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

  public CommissionEntry {
    if (sequenceNo < 1) {
      throw new IllegalArgumentException("sequenceNo must be positive");
    }
    beneficiaryPromoterUserId =
        CommissionAmounts.requireId(beneficiaryPromoterUserId, "beneficiaryPromoterUserId");
    actionType = Objects.requireNonNull(actionType, "actionType");
    amount4 = CommissionAmounts.requirePositive(amount4, "amount4");
    before = Objects.requireNonNull(before, "before");
    after = Objects.requireNonNull(after, "after");
    sourceEventId = CommissionAmounts.requireId(sourceEventId, "sourceEventId");
    cashTier = Objects.requireNonNull(cashTier, "cashTier");
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    reason = Objects.requireNonNull(reason, "reason");

    validateSourceFields(
        actionType,
        orderId,
        fundingLotId,
        allocationId,
        withdrawalRequestId,
        referralBindingVersion,
        cashTier);
    validateReason(actionType, reason);
    validateTransition(actionType, amount4, before, after);
  }

  private static void validateSourceFields(
      CommissionActionType actionType,
      String orderId,
      String fundingLotId,
      String allocationId,
      String withdrawalRequestId,
      Long referralBindingVersion,
      CashTier cashTier) {
    boolean withdrawal =
        actionType == CommissionActionType.WITHDRAWAL_REQUESTED
            || actionType == CommissionActionType.WITHDRAWAL_REJECTED
            || actionType == CommissionActionType.WITHDRAWAL_PAID;
    boolean withdrawalDebtOffset =
        actionType == CommissionActionType.WITHDRAWAL_REJECTION_DEBT_OFFSET;
    if (withdrawalDebtOffset) {
      requireNull(orderId, "orderId");
      requireNull(fundingLotId, "fundingLotId");
      requireNull(allocationId, "allocationId");
      CommissionAmounts.requireId(withdrawalRequestId, "withdrawalRequestId");
      requireNull(referralBindingVersion, "referralBindingVersion");
      if (cashTier != CashTier.NONE) {
        throw new IllegalArgumentException("withdrawal debt offset entries require cash tier NONE");
      }
      return;
    }
    if (withdrawal) {
      requireNull(orderId, "orderId");
      requireNull(fundingLotId, "fundingLotId");
      requireNull(allocationId, "allocationId");
      CommissionAmounts.requireId(withdrawalRequestId, "withdrawalRequestId");
      requireNull(referralBindingVersion, "referralBindingVersion");
      if (cashTier != CashTier.NONE) {
        throw new IllegalArgumentException("withdrawal entries require cash tier NONE");
      }
      return;
    }

    CommissionAmounts.requireId(orderId, "orderId");
    CommissionAmounts.requireId(fundingLotId, "fundingLotId");
    requireNull(withdrawalRequestId, "withdrawalRequestId");
    if (referralBindingVersion == null || referralBindingVersion < 1) {
      throw new IllegalArgumentException("referralBindingVersion must be positive");
    }
    if (cashTier == CashTier.NONE) {
      throw new IllegalArgumentException("cash ledger entries require a paying cash tier");
    }
    if (actionType == CommissionActionType.COMMISSION_FROZEN
        || actionType == CommissionActionType.RECHARGE_REFUND_CLAWBACK) {
      requireNull(allocationId, "allocationId");
    } else {
      CommissionAmounts.requireId(allocationId, "allocationId");
    }
  }

  private static void validateReason(
      CommissionActionType actionType, CommissionEntryReason reason) {
    boolean valid =
        switch (actionType) {
          case COMMISSION_FROZEN -> reason == CommissionEntryReason.RECHARGE_COMMISSION_FROZEN;
          case COMMISSION_RELEASED -> reason == CommissionEntryReason.TASK_COMMISSION_RELEASED;
          case COMMISSION_RELEASE_REVERSED ->
              reason == CommissionEntryReason.TASK_COMMISSION_RELEASE_REVERSED;
          case RECHARGE_REFUND_CLAWBACK ->
              reason == CommissionEntryReason.RECHARGE_UNRELEASED_REFUND;
          case CASH_DEBT_CREATED -> reason == CommissionEntryReason.RELEASE_REVERSAL_SHORTFALL;
          case CASH_DEBT_OFFSET -> reason == CommissionEntryReason.AUTOMATIC_CASH_DEBT_OFFSET;
          case WITHDRAWAL_REJECTION_DEBT_OFFSET ->
              reason == CommissionEntryReason.WITHDRAWAL_REJECTION_CASH_DEBT_OFFSET;
          case WITHDRAWAL_REQUESTED -> reason == CommissionEntryReason.WITHDRAWAL_SUBMITTED;
          case WITHDRAWAL_REJECTED ->
              reason == CommissionEntryReason.WITHDRAWAL_COMPLIANCE_REJECTED
                  || reason == CommissionEntryReason.WITHDRAWAL_ACCOUNT_REJECTED;
          case WITHDRAWAL_PAID ->
              reason == CommissionEntryReason.WITHDRAWAL_OFFLINE_CONFIRMED_PAID;
        };
    if (!valid) {
      throw new IllegalArgumentException("reason is not allowed for actionType");
    }
  }

  private static void validateTransition(
      CommissionActionType actionType,
      Decimal4 amount,
      CommissionBalanceSnapshot before,
      CommissionBalanceSnapshot after) {
    CommissionBalanceSnapshot expected =
        switch (actionType) {
          case COMMISSION_FROZEN -> before.frozenBy(amount);
          case COMMISSION_RELEASED -> before.released(amount);
          case COMMISSION_RELEASE_REVERSED -> before.reverseFromAvailable(amount);
          case RECHARGE_REFUND_CLAWBACK -> before.clawedBack(amount);
          case CASH_DEBT_CREATED -> before.debtCreated(amount);
          case CASH_DEBT_OFFSET -> before.debtOffset(amount);
          case WITHDRAWAL_REJECTION_DEBT_OFFSET -> before.debtOffset(amount);
          case WITHDRAWAL_REQUESTED -> before.withdrawalRequested(amount);
          case WITHDRAWAL_REJECTED -> before.withdrawalRejected(amount);
          case WITHDRAWAL_PAID -> before.withdrawalPaid(amount);
        };
    if (!expected.equals(after)) {
      throw new IllegalArgumentException("before/after transition is inconsistent with action and amount");
    }
  }

  private static void requireNull(Object value, String name) {
    if (value != null) {
      throw new IllegalArgumentException(name + " is not allowed for this action");
    }
  }

  String idempotencyKey() {
    return sourceEventId + '\u0000' + actionType + '\u0000' + beneficiaryPromoterUserId;
  }

  boolean hasSameSemantics(
      Decimal4 expectedAmount,
      String expectedOrderId,
      String expectedFundingLotId,
      String expectedAllocationId,
      String expectedWithdrawalRequestId,
      Long expectedReferralBindingVersion,
      CashTier expectedCashTier,
      Instant expectedCreatedAt,
      CommissionEntryReason expectedReason) {
    return amount4.equals(expectedAmount)
        && Objects.equals(orderId, expectedOrderId)
        && Objects.equals(fundingLotId, expectedFundingLotId)
        && Objects.equals(allocationId, expectedAllocationId)
        && Objects.equals(withdrawalRequestId, expectedWithdrawalRequestId)
        && Objects.equals(referralBindingVersion, expectedReferralBindingVersion)
        && cashTier == expectedCashTier
        && createdAt.equals(expectedCreatedAt)
        && reason == expectedReason;
  }

  @Override
  public String toString() {
    return "CommissionEntry[sequenceNo=%d, actionType=%s, amount4=%s, before=%s, after=%s, cashTier=%s, createdAt=%s, reason=%s]"
        .formatted(sequenceNo, actionType, amount4, before, after, cashTier, createdAt, reason);
  }
}
