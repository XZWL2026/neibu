package com.xzwl.cloud.billing.application.ledger;
import java.time.Instant;
import java.util.List;

/** Internal immutable facts; all accounting decimals travel as strings. */
public interface BenefitSettlementStore {
  record Settlement(String taskId,String ownerId,String status,String chargedPoints4,String refundedPoints4,Instant occurredAt) {}
  record Consumption(String eventId,String kind,String settlementId,String allocationId,String fundingLotId,String orderId,
      String walletId,String walletOwnerUserId,String originalPaidAmount4,String originalPoints4,String successfulPoints4,
      String reversedPoints4,Instant occurredAt) {}
  record Reward(String eventId,String kind,String rewardEntitlementId,String beneficiaryUserId,String sourceSettlementId,
      String sourceAllocationId,String rewardPoints4,Instant occurredAt) {}
  record Receipt(String walletId,String creditedPoints4,String recoveredPoints4,String debtPoints4) {}
  void settle(Settlement command);
  Receipt reward(Reward command);
  String debtPoints(String ownerId);
  List<Consumption> pending(Instant now);
  void acknowledged(String eventId);
  void retry(String eventId,Instant next);
}
