package com.xzwl.cloud.promotion.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

/** Database rows; timestamp text and decimal scales follow the existing Flyway schema. */
public final class PromotionRows {
  private PromotionRows() {}

  @TableName("promotion_applicant_eligibility")
  public static final class ApplicantEligibilityRow {
    @TableId(value = "user_id", type = IdType.INPUT)
    public String userId;
    @TableField("account_type")
    public String accountType;
    @TableField("account_status")
    public String accountStatus;
    @TableField("registered_at")
    public String registeredAt;
    @TableField("source_version")
    public Long sourceVersion;
    @TableField("last_source_event_id")
    public String lastSourceEventId;
    @TableField("updated_at")
    public String updatedAt;

    public ApplicantEligibilityRow() {}

    public ApplicantEligibilityRow(String userId, String accountType, String accountStatus, String registeredAt, Long sourceVersion, String lastSourceEventId, String updatedAt) {
      this.userId = userId;
      this.accountType = accountType;
      this.accountStatus = accountStatus;
      this.registeredAt = registeredAt;
      this.sourceVersion = sourceVersion;
      this.lastSourceEventId = lastSourceEventId;
      this.updatedAt = updatedAt;
    }
  }

  @TableName("promotion_application")
  public static final class ApplicationRow {
    @TableId(value = "application_id", type = IdType.INPUT)
    public String applicationId;
    @TableField("submit_command_id")
    public String submitCommandId;
    @TableField("user_id")
    public String userId;
    @TableField("account_type")
    public String accountType;
    @TableField("account_status")
    public String accountStatus;
    @TableField("registered_at")
    public String registeredAt;
    @TableField("eligibility_source_version")
    public Long eligibilitySourceVersion;
    @TableField("eligibility_snapshot_at")
    public String eligibilitySnapshotAt;
    @TableField("reason")
    public String reason;
    @TableField("planned_channel")
    public String plannedChannel;
    @TableField("agreements_accepted")
    public Boolean agreementsAccepted;
    @TableField("submitted_at")
    public String submittedAt;
    @TableField("status")
    public String status;
    @TableField("decided_by")
    public String decidedBy;
    @TableField("decided_at")
    public String decidedAt;
    @TableField("rejection_reason")
    public String rejectionReason;
  }

  @TableName("promoter_account")
  public static final class PromoterRow {
    @TableId(value = "promoter_user_id", type = IdType.INPUT)
    public String promoterUserId;
    @TableField("invitation_code")
    public String invitationCode;
    @TableField("status")
    public String status;
    @TableField("created_at")
    public String createdAt;
    @TableField("updated_at")
    public String updatedAt;
  }

  @TableName("promotion_command_receipt")
  public static final class ReceiptRow {
    @TableId(value = "command_id", type = IdType.INPUT)
    public String commandId;
    @TableField("command_type")
    public String commandType;
    @TableField("aggregate_id")
    public String aggregateId;
    @TableField("semantics")
    public String semantics;
    @TableField("recorded_at")
    public String recordedAt;
  }

  @TableName("referral_binding")
  public static final class ReferralBindingRow {
    @TableField("referred_user_id")
    public String referredUserId;
    @TableField("version")
    public Long version;
    @TableField("command_id")
    public String commandId;
    @TableField("direct_inviter_user_id")
    public String directInviterUserId;
    @TableField("level1_promoter_user_id")
    public String level1PromoterUserId;
    @TableField("level2_promoter_user_id")
    public String level2PromoterUserId;
    @TableField("point_beneficiary_user_id")
    public String pointBeneficiaryUserId;
    @TableField("cash_promoter_user_id")
    public String cashPromoterUserId;
    @TableField("cash_tier")
    public String cashTier;
    @TableField("effective_at")
    public String effectiveAt;
  }

  @TableName("promotion_benefit_policy")
  public static final class BenefitPolicyRow {
    @TableId(value = "version", type = IdType.INPUT)
    public Long version;
    @TableField("level1_cash_rate6")
    public BigDecimal level1CashRate6;
    @TableField("level2_cash_rate6")
    public BigDecimal level2CashRate6;
    public BigDecimal pointRate6;
    public String effectiveAt;
    public String operatorId;
    public String reason;
    public String changeScope;
    public String changedField;
    public String changedPromoterId;
    public String commandId;
  }

  public static final class BenefitOverrideRow {
    public String promoterUserId;
    public BigDecimal level1CashRate6;
    public BigDecimal level2CashRate6;
  }

  public static final class CustomerPointOverrideRow {
    public String customerUserId;
    public BigDecimal pointRate6;
  }

  @TableName("promotion_commission_account")
  public static final class CommissionAccountRow {
    @TableId(value = "promoter_user_id", type = IdType.INPUT)
    public String promoterUserId;
    @TableField("frozen4")
    public BigDecimal frozen4;
    @TableField("available4")
    public BigDecimal available4;
    @TableField("withdrawing4")
    public BigDecimal withdrawing4;
    @TableField("withdrawn4")
    public BigDecimal withdrawn4;
    @TableField("cash_debt4")
    public BigDecimal cashDebt4;
    @TableField("version")
    public Long version;
  }

  @TableName("promotion_funding_benefit")
  public static final class FundingBenefitRow {
    @TableId(value = "funding_lot_id", type = IdType.INPUT)
    public String fundingLotId;
    @TableField("source_event_id")
    public String sourceEventId;
    @TableField("order_id")
    public String orderId;
    @TableField("wallet_owner_user_id")
    public String walletOwnerUserId;
    @TableField("referral_binding_version")
    public Long referralBindingVersion;
    @TableField("benefit_policy_version")
    public Long benefitPolicyVersion;
    @TableField("promoter_user_id")
    public String promoterUserId;
    @TableField("cash_tier")
    public String cashTier;
    @TableField("cash_rate6")
    public BigDecimal cashRate6;
    @TableField("paid_amount4")
    public BigDecimal paidAmount4;
    @TableField("frozen_commission4")
    public BigDecimal frozenCommission4;
    @TableField("released_commission4")
    public BigDecimal releasedCommission4;
    @TableField("clawed_unreleased_commission4")
    public BigDecimal clawedUnreleasedCommission4;
    @TableField("reversed_released_commission4")
    public BigDecimal reversedReleasedCommission4;
    @TableField("settled_paid_amount4")
    public BigDecimal settledPaidAmount4;
    @TableField("refunded_paid_amount4")
    public BigDecimal refundedPaidAmount4;
    @TableField("occurred_at")
    public String occurredAt;
  }

  @TableName("promotion_commission_ledger")
  public static final class CommissionLedgerRow {
    @TableField("promoter_user_id")
    public String promoterUserId;
    @TableField("sequence_no")
    public Long sequenceNo;
    @TableField("action_type")
    public String actionType;
    @TableField("amount4")
    public BigDecimal amount4;
    @TableField("successful_paid_amount4")
    public BigDecimal successfulPaidAmount4;
    @TableField("source_event_id")
    public String sourceEventId;
    @TableField("order_id")
    public String orderId;
    @TableField("funding_lot_id")
    public String fundingLotId;
    @TableField("allocation_id")
    public String allocationId;
    @TableField("withdrawal_id")
    public String withdrawalId;
    @TableField("created_at")
    public String createdAt;
  }

  @TableName("promotion_payout_account")
  public static final class PayoutAccountRow {
    @TableId(value = "payout_account_id", type = IdType.INPUT)
    public String payoutAccountId;
    @TableField("owner_user_id")
    public String ownerUserId;
    @TableField("payout_type")
    public String payoutType;
    @TableField("payout_account_name")
    public String payoutAccountName;
    @TableField("payout_account_reference")
    public String payoutAccountReference;
    @TableField("status")
    public String status;
    @TableField("source_version")
    public Long sourceVersion;
    @TableField("last_source_event_id")
    public String lastSourceEventId;
    @TableField("updated_at")
    public String updatedAt;

    @TableField("bank_name")
    public String bankName;
    @TableField("qr_code_reference")
    public String qrCodeReference;

    public PayoutAccountRow() {}

    public PayoutAccountRow(String payoutAccountId, String ownerUserId, String payoutType, String payoutAccountName, String payoutAccountReference, String status, Long sourceVersion, String lastSourceEventId, String updatedAt) {
      this.payoutAccountId = payoutAccountId;
      this.ownerUserId = ownerUserId;
      this.payoutType = payoutType;
      this.payoutAccountName = payoutAccountName;
      this.payoutAccountReference = payoutAccountReference;
      this.status = status;
      this.sourceVersion = sourceVersion;
      this.lastSourceEventId = lastSourceEventId;
      this.updatedAt = updatedAt;
    }
  }

  @TableName("promotion_withdrawal")
  public static final class WithdrawalRow {
    @TableId(value = "withdrawal_id", type = IdType.INPUT)
    public String withdrawalId;
    @TableField("request_command_id")
    public String requestCommandId;
    @TableField("promoter_user_id")
    public String promoterUserId;
    @TableField("amount4")
    public BigDecimal amount4;
    @TableField("status")
    public String status;
    @TableField("payout_account_id")
    public String payoutAccountId;
    @TableField("payout_account_source_version")
    public Long payoutAccountSourceVersion;
    @TableField("payout_account_snapshot_at")
    public String payoutAccountSnapshotAt;
    @TableField("payout_type")
    public String payoutType;
    @TableField("payout_account_name")
    public String payoutAccountName;
    @TableField("payout_account_reference")
    public String payoutAccountReference;
    @TableField("rejection_reason")
    public String rejectionReason;
    @TableField("requested_at")
    public String requestedAt;
    @TableField("updated_at")
    public String updatedAt;
  }
}
