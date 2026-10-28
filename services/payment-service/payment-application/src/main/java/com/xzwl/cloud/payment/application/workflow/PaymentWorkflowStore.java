package com.xzwl.cloud.payment.application.workflow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
public interface PaymentWorkflowStore {
  RechargeOrder corporate(PaymentWorkflow.CorporateRecharge request,BigDecimal rate,Instant now);
  TransferPage corporateHistory(int page,int size);
  TransferProof transferProof(String id);
  record Transfer(RechargeOrder order,String fileName,String contentType,int sizeBytes){}
  record TransferPage(List<Transfer> items,long total,int page,int size){}
  RechargeOrder create(PaymentWorkflow.NewRecharge request,BigDecimal rate,Instant now);
  String accountType(String owner);
  List<AccountIdentity> accountIdentities(List<String> owners);
  default List<AccountIdentity> storedAccountIdentities(List<String> owners) { throw new UnsupportedOperationException(); }
  record AccountIdentity(String userId,String accountType,Instant apiSince){}
  boolean firstRecharge(String owner);
  AccountChange beginAccountChange(AccountChange change,boolean enterpriseAllowed);
  void completeAccountChange(String requestId);
  List<AccountChange> pendingAccountChanges();
  record AccountChange(String requestId,String userId,String accountType,boolean createKey,String operatorId,boolean completed){}
  List<RechargeOrder> history(String owner);
  default OrderPage adminOrders(int offset,int limit,Instant to){throw new UnsupportedOperationException();}
  record OrderPage(List<RechargeOrder> items,long total,int offset,int limit){}
  default RechargeOrder manual(PaymentWorkflow.ManualRecharge request,BigDecimal rate,Instant now){return manual(request,rate,now,true);}
  RechargeOrder manual(PaymentWorkflow.ManualRecharge request,BigDecimal rate,Instant now,boolean enterpriseAllowed);
  default RechargeOrder confirmManual(String id,String reviewer,Instant now){return confirmManual(id,reviewer,now,true);}
  RechargeOrder confirmManual(String id,String reviewer,Instant now,boolean enterpriseAllowed);
  RechargeOrder rejectManual(String id,String reviewer,Instant now);
  Optional<RechargeOrder> find(String id);
  RechargeOrder checkout(String id,PaymentProvider.Checkout checkout);
  RechargeOrder confirm(PaymentProvider.VerifiedPayment paid,Instant receivedAt);
  RechargeOrder requestRefund(String id,String ownerId);
  void expire(Instant now);
  List<String> pending(Instant now);
  Optional<Work> claim(String id,Instant now);
  void complete(Work work,String keyOrRefundReference);
  void retry(Work work,Instant now);
  List<PromotionDelivery> pendingPromotions(Instant now);
  void completePromotion(PromotionDelivery delivery);
  void retryPromotion(PromotionDelivery delivery,Instant now);
  record PromotionDelivery(RechargeOrder order,long version,Instant occurredAt){}
  record Work(RechargeOrder order,String stage,String leaseToken){}
}
