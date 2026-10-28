package com.xzwl.cloud.promotion.application.production;

import java.time.Instant;
import java.util.List;
import com.xzwl.cloud.promotion.application.production.PromotionStore.*;

/** Persisted successful-consumption benefits and administrator operations. */
public interface PromotionOperations {
  void recordRecharge(PaidRecharge command);
  void settle(ConsumptionSettlement command);
  record ConsumptionSettlement(String eventId,String kind,String settlementId,String allocationId,
      String fundingLotId,String orderId,String walletId,String walletOwnerUserId,String originalPaidAmount4,
      String originalPoints4,String successfulPoints4,String reversedPoints4,Instant occurredAt) {}
  record RewardDelivery(String eventId,String kind,String rewardEntitlementId,String beneficiaryUserId,
      String sourceSettlementId,String sourceAllocationId,String rewardPoints4,Instant occurredAt) {}
  record RewardReceipt(String walletId,String creditedPoints4,String recoveredPoints4,String debtPoints4) {}
  List<RewardDelivery> pendingRewards(int limit);
  void rewardDelivered(String eventId,RewardReceipt receipt);
  RewardAccount rewards(String owner,int page,int size);
  record RewardEntry(String eventId,String action,String points4,String status,String settlementId,
      String allocationId,String orderId,String creditedPoints4,String recoveredPoints4,String debtPoints4,Instant occurredAt) {}
  record RewardAccount(String earnedPoints4,String postedPoints4,String reversedPoints4,String debtPoints4,
      List<RewardEntry> items,int page,int size,long total) {}

  ReferralAdjustmentPage referralAdjustments(String userId,int page,int size);
  record ReferralAdjustment(String commandId,String userId,String previousInviterId,String inviterId,String operatorId,String reason,Instant occurredAt) {}
  record ReferralAdjustmentPage(List<ReferralAdjustment> items,int page,int size,long total) {}
  Attribution attribution(String userId);
  Attribution rebind(Rebind command);
  record Rebind(String commandId,String userId,String inviterUserId,long expectedVersion,String reason,String adminId) {}
  record ReferralHistoryEntry(ReferralView binding,String operatorId,String reason) {}
  record Attribution(ReferralView current,List<ReferralHistoryEntry> history) {}

  Payout createPayout(CreatePayout command);
  Payout decidePayout(DecidePayout command);
  Payout completePayout(CompletePayout command);
  PayoutDetails payoutDetails(String id,String adminId);
  PayoutPage payouts(String status,String promoterUserId,String month,int page,int size);
  Page<PortalPayoutAccount> payoutAccounts(String promoterUserId,int page,int size);
  record CreatePayout(String commandId,String withdrawalId,String promoterUserId,String settlementMonth,
      String amount4,String payoutAccountId,String adminId,Long expectedDraftVersion) {
    public CreatePayout(String commandId,String withdrawalId,String promoterUserId,String settlementMonth,String amount4,String payoutAccountId,String adminId) {
      this(commandId,withdrawalId,promoterUserId,settlementMonth,amount4,payoutAccountId,adminId,null);
    }
  }
  record DecidePayout(String commandId,String withdrawalId,boolean approved,String reason,String adminId,
      Long expectedAdjustmentVersion,String expectedAmount4) {
    public DecidePayout(String commandId,String withdrawalId,boolean approved,String reason,String adminId) {
      this(commandId,withdrawalId,approved,reason,adminId,null,null);
    }
  }
  record CompletePayout(String commandId,String withdrawalId,String transferReference,Instant paidAt,String adminId) {}
  record Payout(String id,String promoterUserId,String settlementMonth,String amount4,String status,
      String payoutAccountId,String payoutType,String maskedAccountName,String maskedAccountReference,
      String rejectionReason,String transferReference,Instant paidAt,Instant requestedAt,Instant updatedAt,
      String baseAmount4,String adjustmentAmount4,long adjustmentVersion,String finalAmount4) {}
  record PayoutDetails(String id,String payoutType,String accountName,String accountReference,String bankName,
      String qrCodeReference,Instant snapshotAt) {}
  record StatusTotal(String status,long count,String amount4) {}
  record PayoutPage(List<Payout> items,int page,int size,long total,String totalAmount4,List<StatusTotal> statusTotals) {}

  AgentIncomePage agentIncome(String month,String status,String promoterUserId,int page,int size);
  IncomeConsumptionPage incomeConsumptions(String promoterUserId,String month,int page,int size);
  IncomeAdjustmentPage incomeAdjustments(String withdrawalId,int page,int size);
  Payout adjustIncome(AdjustIncome command);
  IncomeDraft incomeDraft(String promoterUserId,String month,int page,int size);
  IncomeDraft adjustIncomeDraft(AdjustIncomeDraft command);
  record AdjustIncomeDraft(String commandId,String promoterUserId,String settlementMonth,String deltaAmount4,String reason,long expectedVersion,String adminId) {}
  record IncomeDraft(String promoterUserId,String settlementMonth,String withdrawalId,String adjustmentAmount4,long adjustmentVersion,IncomeAdjustmentPage adjustments) {}
  record AdjustIncome(String commandId,String withdrawalId,String deltaAmount4,String reason,long expectedVersion,String adminId) {}
  record AgentIncome(String promoterUserId,String promoterStatus,Instant createdAt,long downlineCount,long consumptionCount,
      String consumptionAmount4,String commissionAmount4,String availableAmount4,String debtAmount4,String status,Payout payout) {}
  record AgentIncomePage(List<AgentIncome> items,int page,int size,long total,String totalConsumptionAmount4,
      String totalCommissionAmount4,String pendingPayoutAmount4,List<StatusTotal> statusTotals) {}
  record IncomeConsumption(String allocationId,String customerUserId,String orderId,Instant occurredAt,
      String paidAmount4,String commissionAmount4,String cashRate6) {}
  record IncomeConsumptionPage(List<IncomeConsumption> items,int page,int size,long total) {}
  record IncomeAdjustment(String commandId,String withdrawalId,long version,String deltaAmount4,String reason,
      String operatorId,String status,Instant occurredAt) {}
  record IncomeAdjustmentPage(List<IncomeAdjustment> items,int page,int size,long total) {}

  AuditPage audit(String unit,String userId,String reference,int page,int size);
  record AuditEntry(String id,String unit,String beneficiaryUserId,String action,String amount4,
      String sourceEventId,String orderId,String fundingLotId,String allocationId,String withdrawalId,
      String status,Instant occurredAt) {}
  record AuditPage(List<AuditEntry> items,int page,int size,long total) {}
  Reconciliation reconciliation();
  Reconciliation preview(String adminId,String reason);
  record BalanceDifference(String userId,String dimension,String expected4,String actual4,String difference4) {}
  record Debt(String userId,String unit,String amount4) {}
  record Reconciliation(List<BalanceDifference> differences,List<Debt> debts,long pendingRewardDeliveries,
      Instant generatedAt,boolean dryRun,boolean executable) {}
}
