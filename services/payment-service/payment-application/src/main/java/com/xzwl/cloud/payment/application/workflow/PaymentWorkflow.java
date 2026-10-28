package com.xzwl.cloud.payment.application.workflow;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
public final class PaymentWorkflow {
  private final PaymentWorkflowStore store;private final PaymentProvider provider;
  private final RechargeFulfillmentPort fulfillment;private final Clock clock;
  public PaymentWorkflow(PaymentWorkflowStore store,PaymentProvider provider,RechargeFulfillmentPort fulfillment,Clock clock){
    this.store=store;this.provider=provider;this.fulfillment=fulfillment;this.clock=clock;
  }
  public RechargeOrder corporate(CorporateRecharge request){return store.corporate(request,fulfillment.currentRate(),clock.instant());}
  public PaymentWorkflowStore.TransferPage corporateHistory(int page,int size){return store.corporateHistory(page,size);}
  public TransferProof transferProof(String id){return store.transferProof(id);}
  public record CorporateRecharge(String requestId,String userId,BigDecimal amount4,String remark,TransferProof proof){}
  public RechargeOrder create(NewRecharge request){
    provider.validateAmount(request.amount4(),"CNY");
    var order=store.create(request,fulfillment.currentRate(),clock.instant());
    if(!order.status().equals("PENDING")||!clock.instant().isBefore(order.expiresAt())||order.checkoutUrl()!=null)return order;
    var checkout=provider.create(new PaymentProvider.Charge(order.id(),order.amount4(),"CNY",order.expiresAt(),order.paymentMethod()));
    if(checkout==null||checkout.providerOrderId()==null||checkout.providerOrderId().isBlank()
        ||checkout.providerOrderId().length()>128)throw new IllegalStateException("invalid provider checkout");
    provider.validateCheckout(checkout);
    return store.checkout(order.id(),checkout);
  }
  public RechargeOrder callback(byte[] raw,Map<String,List<String>> headers){
    var paid=provider.verify(raw,headers);
    return store.confirm(paid,clock.instant());
  }
  public RechargeOrder get(String id,String owner){
    var order=store.find(id).orElseThrow(()->new IllegalArgumentException("unknown order"));
    if(!order.userId().equals(owner))throw new Forbidden();
    return order;
  }
  public RechargeOrder refund(String id,String owner){get(id,owner);return store.requestRefund(id,owner);}
  public String accountIdentity(String id) { return store.accountType(id); }
  public List<PaymentWorkflowStore.AccountIdentity> storedAccountIdentities(List<String> ids) { return store.storedAccountIdentities(ids); }
  public List<PaymentWorkflowStore.AccountIdentity> accountIdentities(List<String> ids) { return store.accountIdentities(ids); }
  public Options adminAccount(String id,String request,String type,boolean createKey,String operator,boolean enterpriseAllowed){
    var change=store.beginAccountChange(new PaymentWorkflowStore.AccountChange(request,id,type,createKey,operator,false),enterpriseAllowed);
    applyAccountChange(change);return options(id);
  }
  private void applyAccountChange(PaymentWorkflowStore.AccountChange change){
    if(change.completed())return;
    fulfillment.manageAccount(change);store.completeAccountChange(change.requestId());
  }
  public void processPending(){
    for(var change:store.pendingAccountChanges())try{applyAccountChange(change);}catch(RuntimeException failure){/* Durable account intent retries on the next worker pass. */}
    store.expire(clock.instant());
    for(String id:store.pending(clock.instant())){
      var claimed=store.claim(id,clock.instant());if(claimed.isEmpty())continue;
      var work=claimed.get();var order=work.order();
      try{
        if(work.stage().equals("POLL")){
          var paid=provider.query(new PaymentProvider.Charge(order.id(),order.amount4(),"CNY",order.expiresAt(),order.paymentMethod()));
          if(paid.payment().isPresent())store.confirm(paid.payment().get(),clock.instant());
          else if(paid.finalWithoutPayment())store.complete(work,"");
          else store.retry(work,clock.instant());
        }else if(work.stage().equals("FULFILL")){
          String key=fulfillment.ensurePrimary(order);fulfillment.credit(order,key);store.complete(work,key);
        }else{
          if(order.fulfilled())fulfillment.reserveRefund(order);
          String reference=provider.refund(new PaymentProvider.Refund(order.id(),order.transactionId(),"refund:"+order.id(),order.amount4(),"CNY"));
          if(reference==null||reference.isBlank()||reference.length()>128)throw new IllegalStateException("refund not confirmed");
          store.complete(work,reference);
        }
      }catch(RuntimeException failure){store.retry(work,clock.instant());}
    }
  }
  public void processPromotions(){
    for(var delivery:store.pendingPromotions(clock.instant())){
      try{fulfillment.promote(delivery);store.completePromotion(delivery);}
      catch(RuntimeException failure){store.retryPromotion(delivery,clock.instant());}
    }
  }
  public Options apiCreationOptions(){return new Options(null,"API",true,fulfillment.currentRate().setScale(4).toPlainString(),RechargeRules.rules(true));}
  public Options options(String owner){
    String type=store.accountType(owner);boolean first=store.firstRecharge(owner);return new Options(owner,type,first,fulfillment.currentRate().setScale(4).toPlainString(),RechargeRules.rules(first));
  }
  public List<RechargeOrder> history(String owner){return store.history(Objects.requireNonNull(owner));}
  public RechargeOrder adminOrder(String id){return store.find(id).orElseThrow(()->new IllegalArgumentException("unknown order"));}
  public List<RechargeOrder> adminHistory(){return store.history(null);}
  public PaymentWorkflowStore.OrderPage adminOrders(int offset,int limit,java.time.Instant to){return store.adminOrders(offset,limit,to);}
  public RechargeOrder manual(ManualRecharge request){return manual(request,true);}
  public RechargeOrder manual(ManualRecharge request,boolean enterpriseAllowed){return store.manual(request,fulfillment.currentRate(),clock.instant(),enterpriseAllowed);}
  public RechargeOrder confirmManual(String id,String reviewer){return confirmManual(id,reviewer,true);}
  public RechargeOrder confirmManual(String id,String reviewer,boolean enterpriseAllowed){return store.confirmManual(id,reviewer,clock.instant(),enterpriseAllowed);}
  public RechargeOrder rejectManual(String id,String reviewer){return store.rejectManual(id,reviewer,clock.instant());}
  public record Options(String userId,String accountType,boolean firstRecharge,String pointsPerYuan4,List<RechargeRules.Rule> rules){}
  public record ManualRecharge(String requestId,String userId,BigDecimal amount4,RechargeType accountType,String enterpriseName,String remark,String submittedBy){}
  public record NewRecharge(String requestId,String userId,BigDecimal amount4,RechargeType rechargeType,String enterpriseName,com.xzwl.cloud.payment.domain.order.PaymentMethod paymentMethod){
    public NewRecharge(String requestId,String userId,BigDecimal amount4,RechargeType rechargeType,String enterpriseName){this(requestId,userId,amount4,rechargeType,enterpriseName,null);}
  }
  public static final class Forbidden extends RuntimeException {}
}
