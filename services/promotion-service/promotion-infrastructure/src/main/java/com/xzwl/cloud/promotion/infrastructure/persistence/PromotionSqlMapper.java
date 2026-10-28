package com.xzwl.cloud.promotion.infrastructure.persistence;

import com.xzwl.cloud.promotion.infrastructure.persistence.PromotionRows.*;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Explicit row locks and atomic transaction writes, mapped in PromotionSqlMapper.xml. */
@Mapper
public interface PromotionSqlMapper {
  @org.apache.ibatis.annotations.Select("SELECT id FROM promotion_referral_guard WHERE id=1 FOR UPDATE")
  int lockReferralGraph();
  @org.apache.ibatis.annotations.Insert("INSERT INTO promotion_invitation(user_id,invitation_code,short_id) VALUES(#{owner},#{code},#{number}) ON DUPLICATE KEY UPDATE user_id=user_id")
  int ensureInvitation(@Param("owner") String owner,@Param("code") String code,@Param("number") int number);
  @org.apache.ibatis.annotations.Select("SELECT short_id FROM promotion_invitation WHERE user_id=#{owner} FOR UPDATE")
  Long lockInvitation(@Param("owner") String owner);
  @org.apache.ibatis.annotations.Select("SELECT short_id FROM promotion_invitation WHERE user_id=#{owner}")
  Long invitationNumber(@Param("owner") String owner);
  @org.apache.ibatis.annotations.Select("SELECT user_id FROM promotion_invitation WHERE invitation_code=#{code}")
  String invitationOwner(@Param("code") String code);
  @org.apache.ibatis.annotations.Select("SELECT user_id FROM promotion_invitation WHERE short_id=#{number}")
  String numberedInvitationOwner(@Param("number") long number);
  @org.apache.ibatis.annotations.Select("SELECT r.* FROM referral_binding r WHERE r.direct_inviter_user_id=#{owner} AND NOT EXISTS (SELECT 1 FROM referral_binding newer WHERE newer.referred_user_id=r.referred_user_id AND newer.version>r.version) ORDER BY r.effective_at DESC,r.referred_user_id LIMIT #{size} OFFSET #{offset}")
  List<ReferralBindingRow> directInvites(@Param("owner") String owner,@Param("offset") long offset,@Param("size") int size);
  @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM referral_binding r WHERE r.direct_inviter_user_id=#{owner} AND NOT EXISTS (SELECT 1 FROM referral_binding newer WHERE newer.referred_user_id=r.referred_user_id AND newer.version>r.version)")
  long directInviteCount(@Param("owner") String owner);

  int updateEligibility(
      @Param("accountType") String accountType,
      @Param("accountStatus") String accountStatus,
      @Param("registeredAt") String registeredAt,
      @Param("sourceVersion") Long sourceVersion,
      @Param("lastSourceEventId") String lastSourceEventId,
      @Param("updatedAt") String updatedAt,
      @Param("userId") String userId);

  int updatePayoutAccount(
      @Param("ownerUserId") String ownerUserId,
      @Param("payoutType") String payoutType,
      @Param("payoutAccountName") String payoutAccountName,
      @Param("payoutAccountReference") String payoutAccountReference,
      @Param("status") String status,
      @Param("sourceVersion") Long sourceVersion,
      @Param("lastSourceEventId") String lastSourceEventId,
      @Param("updatedAt") String updatedAt,
      @Param("payoutAccountId") String payoutAccountId);

  int submitApplication(
      @Param("applicationId") String applicationId,
      @Param("submitCommandId") String submitCommandId,
      @Param("userId") String userId,
      @Param("accountType") String accountType,
      @Param("accountStatus") String accountStatus,
      @Param("registeredAt") String registeredAt,
      @Param("eligibilitySourceVersion") Long eligibilitySourceVersion,
      @Param("eligibilitySnapshotAt") String eligibilitySnapshotAt,
      @Param("reason") String reason,
      @Param("plannedChannel") String plannedChannel,
      @Param("agreementsAccepted") Boolean agreementsAccepted,
      @Param("submittedAt") String submittedAt);

  int reviewApplication(
      @Param("status") String status,
      @Param("decidedBy") String decidedBy,
      @Param("decidedAt") String decidedAt,
      @Param("rejectionReason") String rejectionReason,
      @Param("applicationId") String applicationId);

  int createPromoter(
      @Param("promoterUserId") String promoterUserId,
      @Param("invitationCode") String invitationCode,
      @Param("createdAt") String createdAt,
      @Param("updatedAt") String updatedAt);

  int createCommissionAccount(
      @Param("promoterUserId") String promoterUserId);

  int changePromoterStatus(
      @Param("status") String status,
      @Param("updatedAt") String updatedAt,
      @Param("promoterUserId") String promoterUserId);

  int insertReferralBinding(
      @Param("referredUserId") String referredUserId,
      @Param("version") Long version,
      @Param("commandId") String commandId,
      @Param("directInviterUserId") String directInviterUserId,
      @Param("level1PromoterUserId") String level1PromoterUserId,
      @Param("level2PromoterUserId") String level2PromoterUserId,
      @Param("pointBeneficiaryUserId") String pointBeneficiaryUserId,
      @Param("cashPromoterUserId") String cashPromoterUserId,
      @Param("cashTier") String cashTier,
      @Param("effectiveAt") String effectiveAt);

  int freezeAccount(
      @Param("frozen4") BigDecimal frozen4,
      @Param("version") Long version,
      @Param("promoterUserId") String promoterUserId,
      @Param("expectedVersion") Long expectedVersion);

  int createFundingBenefit(
      @Param("fundingLotId") String fundingLotId,
      @Param("sourceEventId") String sourceEventId,
      @Param("orderId") String orderId,
      @Param("walletOwnerUserId") String walletOwnerUserId,
      @Param("referralBindingVersion") Long referralBindingVersion,
      @Param("benefitPolicyVersion") Long benefitPolicyVersion,
      @Param("promoterUserId") String promoterUserId,
      @Param("cashTier") String cashTier,
      @Param("cashRate6") BigDecimal cashRate6,
      @Param("paidAmount4") BigDecimal paidAmount4,
      @Param("frozenCommission4") BigDecimal frozenCommission4,
      @Param("occurredAt") String occurredAt);

  int releaseAccount(
      @Param("frozen4") BigDecimal frozen4,
      @Param("available4") BigDecimal available4,
      @Param("cashDebt4") BigDecimal cashDebt4,
      @Param("version") Long version,
      @Param("promoterUserId") String promoterUserId,
      @Param("expectedVersion") Long expectedVersion);

  int settleFundingBenefit(
      @Param("releasedCommission4") BigDecimal releasedCommission4,
      @Param("settledPaidAmount4") BigDecimal settledPaidAmount4,
      @Param("fundingLotId") String fundingLotId);

  int refundFundingBenefit(
      @Param("refundedPaidAmount4") BigDecimal refundedPaidAmount4,
      @Param("clawedUnreleasedCommission4") BigDecimal clawedUnreleasedCommission4,
      @Param("reversedReleasedCommission4") BigDecimal reversedReleasedCommission4,
      @Param("fundingLotId") String fundingLotId);

  int reserveWithdrawal(
      @Param("available4") BigDecimal available4,
      @Param("withdrawing4") BigDecimal withdrawing4,
      @Param("version") Long version,
      @Param("promoterUserId") String promoterUserId,
      @Param("expectedVersion") Long expectedVersion);

  int requestWithdrawal(
      @Param("withdrawalId") String withdrawalId,
      @Param("requestCommandId") String requestCommandId,
      @Param("promoterUserId") String promoterUserId,
      @Param("amount4") BigDecimal amount4,
      @Param("payoutAccountId") String payoutAccountId,
      @Param("payoutAccountSourceVersion") Long payoutAccountSourceVersion,
      @Param("payoutAccountSnapshotAt") String payoutAccountSnapshotAt,
      @Param("payoutType") String payoutType,
      @Param("payoutAccountName") String payoutAccountName,
      @Param("payoutAccountReference") String payoutAccountReference,
      @Param("payoutBankName") String payoutBankName,
      @Param("payoutQrCodeReference") String payoutQrCodeReference,
      @Param("requestedAt") String requestedAt,
      @Param("updatedAt") String updatedAt);

  int returnWithdrawal(
      @Param("available4") BigDecimal available4,
      @Param("withdrawing4") BigDecimal withdrawing4,
      @Param("cashDebt4") BigDecimal cashDebt4,
      @Param("version") Long version,
      @Param("promoterUserId") String promoterUserId,
      @Param("expectedVersion") Long expectedVersion);

  int decideWithdrawal(
      @Param("status") String status,
      @Param("rejectionReason") String rejectionReason,
      @Param("updatedAt") String updatedAt,
      @Param("withdrawalId") String withdrawalId,
      @Param("expectedStatus") String expectedStatus);

  int payWithdrawal(
      @Param("withdrawing4") BigDecimal withdrawing4,
      @Param("withdrawn4") BigDecimal withdrawn4,
      @Param("version") Long version,
      @Param("promoterUserId") String promoterUserId,
      @Param("expectedVersion") Long expectedVersion);

  int completeWithdrawal(
      @Param("updatedAt") String updatedAt,
      @Param("withdrawalId") String withdrawalId);

  int recordCommand(
      @Param("commandId") String commandId,
      @Param("commandType") String commandType,
      @Param("aggregateId") String aggregateId,
      @Param("semantics") String semantics,
      @Param("recordedAt") String recordedAt);

  int insertLedger(
      @Param("promoterUserId") String promoterUserId,
      @Param("sequenceNo") Long sequenceNo,
      @Param("actionType") String actionType,
      @Param("amount4") BigDecimal amount4,
      @Param("sourceEventId") String sourceEventId,
      @Param("orderId") String orderId,
      @Param("fundingLotId") String fundingLotId,
      @Param("allocationId") String allocationId,
      @Param("withdrawalId") String withdrawalId,
      @Param("createdAt") String createdAt);

  int insertTransition(
      @Param("commandId") String commandId,
      @Param("withdrawalId") String withdrawalId,
      @Param("actionType") String actionType,
      @Param("operatorId") String operatorId,
      @Param("reason") String reason,
      @Param("changedAt") String changedAt,
      @Param("resultStatus") String resultStatus);

  @org.apache.ibatis.annotations.Update("UPDATE promotion_commission_ledger SET successful_paid_amount4 = #{amount} WHERE source_event_id = #{event}")
  int recordSuccessfulPaidAmount(@Param("event") String event, @Param("amount") BigDecimal amount);

  List<ApplicationRow> lockApplication(@Param("id") String id);

  List<ApplicantEligibilityRow> lockEligibility(@Param("id") String id);

  List<PayoutAccountRow> lockPayoutAccount(@Param("id") String id);

  List<PromoterRow> lockPromoter(@Param("id") String id);

  List<WithdrawalRow> lockWithdrawal(@Param("id") String id);

  List<CommissionAccountRow> lockAccount(@Param("id") String id);

  List<FundingBenefitRow> lockFunding(@Param("id") String id);

  List<ReferralBindingRow> lockCurrentReferral(@Param("id") String id);
}
