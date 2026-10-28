package com.xzwl.cloud.billing.infrastructure.persistence;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BenefitSettlementMapper {
  class SettlementRow {public String taskId,walletId,facts;}
  class AllocationRow {public String allocationId,settlementId,payload;public BigDecimal successfulPoints4,reversedPoints4;}
  class Account {public String beneficiaryUserId,walletId;public BigDecimal debtPoints4;}
  class Entitlement {public String entitlementId,beneficiaryUserId,settlementId,allocationId,lotId;public BigDecimal originalPoints4,reversedPoints4;}
  class RefundReceipt {public BigDecimal requestedPoints4,creditedPoints4;}
  @Select("SELECT * FROM billing_reward_refund_receipt WHERE wallet_id=#{wallet} AND refund_id=#{id}")
  RefundReceipt refundReceipt(@Param("wallet")String wallet,@Param("id")String id);
  @Insert("INSERT INTO billing_reward_refund_receipt(wallet_id,refund_id,requested_points4,credited_points4) VALUES(#{wallet},#{id},#{requested},#{credited})")
  void refundReceiptInsert(@Param("wallet")String wallet,@Param("id")String id,@Param("requested")BigDecimal requested,@Param("credited")BigDecimal credited);
  @Select("SELECT * FROM billing_reward_entitlement WHERE lot_id=#{lot}") Entitlement entitlementForLot(String lot);
  class RefundRow {public String refundId,allocationId,occurredAt;public BigDecimal points4;}
  @Insert("INSERT INTO billing_benefit_refund(refund_id,allocation_id,points4,occurred_at) VALUES(#{refundId},#{allocationId},#{points4},#{occurredAt})")
  void refundInsert(RefundRow row);
  @Select("SELECT * FROM billing_benefit_refund WHERE allocation_id=#{id} ORDER BY refund_id") List<RefundRow> refunds(String id);
  class Event {public String eventId,facts,receipt,payload;}
  @Select("SELECT * FROM billing_benefit_settlement WHERE task_id=#{id}") SettlementRow settlement(String id);
  @Insert("INSERT INTO billing_benefit_settlement(task_id,wallet_id,facts) VALUES(#{task},#{wallet},#{facts})")
  void settlementInsert(@Param("task")String task,@Param("wallet")String wallet,@Param("facts")String facts);
  @Select("SELECT * FROM billing_benefit_allocation WHERE allocation_id=#{id}") AllocationRow allocation(String id);
  @Insert("INSERT INTO billing_benefit_allocation(allocation_id,settlement_id,successful_points4,payload) VALUES(#{allocationId},#{settlementId},#{successfulPoints4},#{payload})")
  void allocationInsert(AllocationRow row);
  @Update("UPDATE billing_benefit_allocation SET reversed_points4=reversed_points4+#{amount} WHERE allocation_id=#{id} AND reversed_points4+#{amount}<=successful_points4")
  int reverse(@Param("id")String id,@Param("amount")BigDecimal amount);
  @Insert("INSERT INTO billing_benefit_delivery(event_id,payload) VALUES(#{id},#{payload})") void enqueue(@Param("id")String id,@Param("payload")String payload);
  @Select("SELECT event_id,payload FROM billing_benefit_delivery WHERE completed=false AND next_attempt_at<=#{now} ORDER BY next_attempt_at,event_id LIMIT 100") List<Event> pending(long now);
  @Update("UPDATE billing_benefit_delivery SET completed=true WHERE event_id=#{id}") void acknowledge(String id);
  @Update("UPDATE billing_benefit_delivery SET next_attempt_at=#{next} WHERE event_id=#{id} AND completed=false") void retry(@Param("id")String id,@Param("next")long next);
  @Select("SELECT * FROM billing_reward_account WHERE beneficiary_user_id=#{id}") Account account(String id);
  @Select("SELECT * FROM billing_reward_account WHERE beneficiary_user_id=#{id} FOR UPDATE") Account lockAccount(String id);
  @Insert("INSERT INTO billing_reward_account(beneficiary_user_id,wallet_id) VALUES(#{user},#{wallet}) ON DUPLICATE KEY UPDATE beneficiary_user_id=beneficiary_user_id")
  void ensureAccount(@Param("user")String user,@Param("wallet")String wallet);
  @Update("UPDATE billing_reward_account SET debt_points4=#{debt} WHERE beneficiary_user_id=#{user}") void debt(@Param("user")String user,@Param("debt")BigDecimal debt);
  @Select("SELECT * FROM billing_reward_entitlement WHERE entitlement_id=#{id}") Entitlement entitlement(String id);
  @Insert("INSERT INTO billing_reward_entitlement(entitlement_id,beneficiary_user_id,settlement_id,allocation_id,original_points4,lot_id) VALUES(#{entitlementId},#{beneficiaryUserId},#{settlementId},#{allocationId},#{originalPoints4},#{lotId})")
  void entitlementInsert(Entitlement row);
  @Update("UPDATE billing_reward_entitlement SET reversed_points4=reversed_points4+#{points} WHERE entitlement_id=#{id} AND reversed_points4+#{points}<=original_points4")
  int entitlementReverse(@Param("id")String id,@Param("points")BigDecimal points);
  @Select("SELECT * FROM billing_reward_event WHERE event_id=#{id}") Event rewardEvent(String id);
  @Insert("INSERT INTO billing_reward_event(event_id,facts,receipt) VALUES(#{id},#{facts},#{receipt})") void rewardEventInsert(@Param("id")String id,@Param("facts")String facts,@Param("receipt")String receipt);
  @Select("SELECT wallet_id FROM recharge_credit_record WHERE user_id=#{id} ORDER BY paid_at,order_id LIMIT 1") String originalWallet(String id);
}
