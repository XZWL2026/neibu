package com.xzwl.cloud.promotion.application.production;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Production persistence boundary for promotion commands and administrator queries. */
public interface PromotionStore {
  default java.util.List<String> invitationSources(java.util.List<String> owners) { throw new UnsupportedOperationException(); }
  BenefitSettings benefitSettings();
  BenefitSettings changeBenefitRate(ChangeBenefitRate command);
  List<BenefitHistory> benefitSettingsHistory();
  record ChangeBenefitRate(String commandId, long expectedVersion, String promoterId, String field,
      String rate, String reason, String adminId) {}
  record BenefitGlobal(String invitationPointsRate, String defaultLevel1CashRate, String defaultLevel2CashRate) {}
  record BenefitPromoter(String id, String name, String level1CashRate, String level2CashRate,
      String maskedPhone, String effectiveAt, boolean enabled) {
    public BenefitPromoter(String id, String name, String level1CashRate, String level2CashRate) {
      this(id,name,level1CashRate,level2CashRate,null,null,false);
    }
  }
  record BenefitCustomer(String id, String name, String maskedPhone, String invitationPointsRate,
      String effectiveAt, boolean enabled, String accountType, boolean cashAgent) {}
  record BenefitSettings(long version, String effectiveAt, String operator, String reason,
      BenefitGlobal global, List<BenefitPromoter> promoters, List<BenefitCustomer> customers) {
    public BenefitSettings(long version, String effectiveAt, String operator, String reason,
        BenefitGlobal global, List<BenefitPromoter> promoters) {
      this(version,effectiveAt,operator,reason,global,promoters,List.of());
    }
  }
  record BenefitHistory(long version, String scope, String promoterId, String invitationPointsRate,
      String defaultLevel1CashRate, String defaultLevel2CashRate, String level1CashRate, String level2CashRate,
      String effectiveAt, String operator, String reason, String field, String beforeRate, String afterRate) {
    public BenefitHistory(long version, String scope, String promoterId, String invitationPointsRate,
        String defaultLevel1CashRate, String defaultLevel2CashRate, String level1CashRate, String level2CashRate,
        String effectiveAt, String operator, String reason) {
      this(version,scope,promoterId,invitationPointsRate,defaultLevel1CashRate,defaultLevel2CashRate,
          level1CashRate,level2CashRate,effectiveAt,operator,reason,null,null,null);
    }
  }

  ParticipationView participation(String userId, int page, int size);
  ReferralView bindInvitation(String userId, String code);
  record LiveApplicant(String userId, String accountType, boolean enabled, java.time.Instant registeredAt, long version, String nickname, String maskedPhone) {}
  record DirectInvite(String nickname, String maskedPhone, java.time.Instant joinedAt) {}
  record ParticipationView(String accountType, boolean eligible, String invitationCode, ApplicationView application, PromoterView promoter, Page<DirectInvite> directInvites) {}

  ApplicantEligibilityView projectApplicantEligibility(ProjectApplicantEligibility command);
  PayoutAccountView projectPayoutAccount(ProjectPayoutAccount command);
  ApplicationView submitApplication(SubmitApplication command);
  ApplicationView reviewApplication(ReviewApplication command);
  PromoterView createAdminPromoter(AdminCreatePromoter command);
  Page<PromoterView> listPromoters(String userId, int page, int size);
  PromoterView changePromoterStatus(ChangePromoterStatus command);
  ReferralView bindReferral(BindReferral command);
  String projectPaidRecharge(PaidRecharge command);
  record PaidRecharge(String orderId, String userId, BigDecimal amount4, Instant createdAt, Instant paidAt, Instant refundedAt) {}
  CommissionView freezeRecharge(FreezeRecharge command);
  CommissionView settleConsumption(SettleConsumption command);
  CommissionView refundRecharge(RefundRecharge command);
  WithdrawalView requestWithdrawal(RequestWithdrawal command);
  WithdrawalView decideWithdrawal(DecideWithdrawal command);
  WithdrawalView completeWithdrawal(CompleteWithdrawal command);
  ApplicationView getApplication(String applicationId);
  PromoterView getPromoter(String userId);
  ReferralView getReferral(String userId);
  CommissionView getCommission(String promoterUserId);
  WithdrawalView getWithdrawal(String withdrawalId);
  Page<ApplicationView> listApplications(String status, String userId, int page, int size);
  Page<WithdrawalView> listWithdrawals(String status, String promoterUserId, int page, int size);
  Page<CommissionEntryView> listCommissionEntries(String promoterUserId, int page, int size);
  Page<ReferralView> listReferralHistory(String referredUserId, int page, int size);

  PortalEligibility portalEligibility(String userId);
  PortalOverview portalOverview(String userId);
  Page<PortalDownline> portalDownlines(String userId, int page, int size);
  Page<PortalRecharge> portalRecharges(String userId, int page, int size);
  Page<PortalConsumption> portalConsumptions(String userId, int page, int size);
  FinancialPage<PortalRecharge> portalRecharges(String userId, Instant from, Instant to, int page, int size);
  FinancialPage<PortalConsumption> portalConsumptions(String userId, Instant from, Instant to, int page, int size);
  record FinancialPage<T>(List<T> items, int page, int size, long total, String totalAmount4) {}
  record PortalPaidMonth(String month, String amount4) {}
  FinancialPage<PortalPaidMonth> portalPaidMonths(String userId, int page, int size);
  FinancialPage<WithdrawalView> portalWithdrawals(String userId, Instant from, Instant to, int page, int size);
  Page<PortalPayoutAccount> portalPayoutAccounts(String userId, int page, int size);
  PortalPayoutAccount savePortalPayoutAccount(SavePayoutAccount command);

  record PortalEligibility(String userId, boolean eligible) {}
  record PortalOverview(PromoterView promoter, CommissionView commission, long downlineCount,
      String paidAmount4, String settledPaidAmount4, String refundedPaidAmount4,
      long levelOneCount, long levelTwoCount, String releasedCommissionAmount4, String totalCommissionAmount4) {}
  record PortalDownline(String maskedUserId, String nickname, String maskedPhone, String accountType,
      String accountStatus, Instant registeredAt, int level, Instant boundAt, BigDecimal balance4,
      String totalRechargeAmount4, String consumedPoints4) {}
  record PortalDownlineFacts(String nickname, String maskedPhone, boolean enabled,
      String totalRechargeAmount4, String consumedPoints4, Instant registeredAt) {}
  record PortalRecharge(String orderId, String maskedUserId, String cashTier, String paidAmount4,
      String refundedPaidAmount4, String commissionAmount4, Instant occurredAt, String nickname, String maskedPhone) {}
  record PortalConsumption(String allocationId, String maskedUserId, String cashTier, String type,
      String commissionAmount4, String paidAmount4, Instant occurredAt, String nickname, String maskedPhone,
      String points4, String balanceBeforePoints4, String balanceAfterPoints4, String balanceBeforeAmount4, String balanceAfterAmount4) {}
  record PortalAllocationLookup(String ownerId, String allocationId, String fundingLotId) {}
  record PortalAllocationFacts(String type, String points4, String balanceBeforePoints4, String balanceAfterPoints4, String balanceBeforeAmount4, String balanceAfterAmount4) {}
  record PortalPayoutAccount(String id, String payoutType, String maskedPayoutAccountName,
      String maskedPayoutAccountReference, String bankName, boolean qrCodeConfigured,
      PayoutAccountStatus status, long sourceVersion, Instant updatedAt) {}
  record SavePayoutAccount(String commandId, String payoutAccountId, String ownerUserId,
      String payoutType, String payoutAccountName, String payoutAccountReference, String bankName,
      String qrCodeReference, long expectedVersion, Instant occurredAt) {}

  enum ApplicationDecision { APPROVE, REJECT }
  enum PromoterStatus { ENABLED, DISABLED }
  enum PayoutAccountStatus { ACTIVE, DISABLED }
  enum WithdrawalStatus { WITHDRAWING, PENDING_PAYOUT, PAID, REJECTED }

  record ProjectApplicantEligibility(
      String sourceEventId, String userId, String accountType, String accountStatus,
      Instant registeredAt, long sourceVersion, Instant occurredAt) {}

  record ProjectPayoutAccount(
      String sourceEventId, String payoutAccountId, String ownerUserId, String payoutType,
      String payoutAccountName, String payoutAccountReference, PayoutAccountStatus status,
      long sourceVersion, Instant occurredAt) {}

  record SubmitApplication(
      String commandId, String applicationId, String userId, String reason,
      String plannedChannel, boolean agreementsAccepted, Instant submittedAt) {}

  record ReviewApplication(
      String commandId, String applicationId, String adminId, ApplicationDecision decision,
      String rejectionReason, Instant decidedAt) {}

  record AdminCreatePromoter(String commandId, String userId, String adminId, Instant occurredAt) {}

  record ChangePromoterStatus(
      String commandId, String promoterUserId, PromoterStatus status, String adminId,
      Instant changedAt) {}

  record BindReferral(
      String commandId, String referredUserId, String directInviterUserId,
      long expectedVersion, Instant effectiveAt) {}

  record FreezeRecharge(
      String sourceEventId, String orderId, String fundingLotId, String walletOwnerUserId,
      long referralBindingVersion, long benefitPolicyVersion, BigDecimal paidAmount,
      Instant occurredAt) {}

  record SettleConsumption(
      String sourceEventId, String allocationId, String fundingLotId,
      BigDecimal successfulPaidAmount, boolean finalDepletion, Instant occurredAt) {}

  record RefundRecharge(
      String sourceEventId, String fundingLotId, BigDecimal refundedPaidAmount,
      Instant occurredAt) {}

  record RequestWithdrawal(
      String commandId, String withdrawalId, String promoterUserId, BigDecimal amount,
      String payoutAccountId, Instant requestedAt) {}

  record DecideWithdrawal(
      String commandId, String withdrawalId, String adminId, boolean approved,
      String rejectionReason, Instant decidedAt) {}

  record CompleteWithdrawal(
      String commandId, String withdrawalId, String adminId, Instant completedAt) {}

  record ApplicationView(
      String id, String userId, String accountType, String accountStatus, String reason,
      String plannedChannel, long eligibilitySourceVersion, Instant eligibilitySnapshotAt,
      String status, Instant submittedAt, String decidedBy, Instant decidedAt,
      String rejectionReason) {}

  record ApplicantEligibilityView(
      String userId, String accountType, String accountStatus, Instant registeredAt,
      long sourceVersion, Instant updatedAt) {}

  record PayoutAccountView(
      String id, String ownerUserId, String payoutType, String payoutAccountName,
      String maskedPayoutAccountReference, PayoutAccountStatus status, long sourceVersion,
      Instant updatedAt) {}

  record PromoterView(
      String userId, String invitationCode, PromoterStatus status, Instant createdAt,
      Instant updatedAt) {}

  record ReferralView(
      String referredUserId, String directInviterUserId, String level1PromoterUserId,
      String level2PromoterUserId, String pointBeneficiaryUserId, long version,
      Instant effectiveAt) {}

  record CommissionView(
      String promoterUserId, BigDecimal frozen, BigDecimal available, BigDecimal withdrawing,
      BigDecimal withdrawn, BigDecimal cashDebt, long version, BigDecimal changedAmount) {}

  record CommissionEntryView(
      long sequenceNo, String promoterUserId, String actionType, BigDecimal amount,
      String sourceEventId, String orderId, String fundingLotId, String allocationId,
      String withdrawalId, Instant createdAt) {}

  record WithdrawalView(
      String id, String promoterUserId, BigDecimal amount, WithdrawalStatus status,
      String payoutAccountId, long payoutAccountSourceVersion, Instant payoutAccountSnapshotAt,
      String payoutType, String payoutAccountName, String maskedPayoutAccountNo,
      String rejectionReason, Instant requestedAt, Instant updatedAt) {}

  record Page<T>(List<T> items, int page, int size, long total) {
    public Page { items = List.copyOf(items); }
  }
}
