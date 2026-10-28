package com.xzwl.cloud.payment.infrastructure.workflow;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xzwl.cloud.payment.application.workflow.*;
import com.xzwl.cloud.payment.domain.order.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.xzwl.cloud.payment.infrastructure.workflow.PaymentRows.*;

public final class MybatisPaymentWorkflowStore implements PaymentWorkflowStore {
  private final PaymentMappers.Orders orders;private final PaymentMappers.Accounts accounts;
  private final PaymentMappers.Requests requests;private final PaymentMappers.Work work;
  private final TransactionTemplate tx;
  private final java.util.function.Function<String,String> existingIdentity;
  private final com.xzwl.cloud.payment.infrastructure.shouqianba.PaymentDataCipher cipher;
  public MybatisPaymentWorkflowStore(PaymentMappers.Orders orders,PaymentMappers.Accounts accounts,
      PaymentMappers.Requests requests,PaymentMappers.Work work,TransactionTemplate tx){
    this(orders,accounts,requests,work,tx,null);
  }
  public MybatisPaymentWorkflowStore(PaymentMappers.Orders orders,PaymentMappers.Accounts accounts,
      PaymentMappers.Requests requests,PaymentMappers.Work work,TransactionTemplate tx,
      com.xzwl.cloud.payment.infrastructure.shouqianba.PaymentDataCipher cipher){
    this(orders,accounts,requests,work,tx,cipher,owner->null);
  }
  public MybatisPaymentWorkflowStore(PaymentMappers.Orders orders,PaymentMappers.Accounts accounts,
      PaymentMappers.Requests requests,PaymentMappers.Work work,TransactionTemplate tx,
      com.xzwl.cloud.payment.infrastructure.shouqianba.PaymentDataCipher cipher,java.util.function.Function<String,String> existingIdentity){
    this.existingIdentity=existingIdentity;this.cipher=cipher;
    this.orders=orders;this.accounts=accounts;this.requests=requests;this.work=work;this.tx=tx;
    tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }
  public RechargeOrder create(PaymentWorkflow.NewRecharge request,BigDecimal rate,Instant now){
    Objects.requireNonNull(request);id(request.requestId());id(request.userId());
    BigDecimal amount=RechargeRules.yuan(request.amount4());BigDecimal snapshot=money(rate);Objects.requireNonNull(request.rechargeType());
    // Do not accept cash for points that cannot fit Billing's DECIMAL(38,4).
    money(amount.multiply(snapshot));
    String enterprise=request.enterpriseName()==null?null:request.enterpriseName().strip();
    if(enterprise!=null&&enterprise.length()>200)throw new IllegalArgumentException("enterprise name too long");
    if(request.rechargeType()!=RechargeType.ENTERPRISE&&enterprise!=null&&!enterprise.isEmpty())throw new IllegalArgumentException("enterprise name only for enterprise account");
    return tx.execute(s->{
      var account=account(request.userId());
      var prior=requests.selectById(request.requestId());
      if(prior!=null){
        var existing=required(prior.orderId);
        if(!prior.userId.equals(request.userId())||!"PROVIDER".equals(existing.source)||(!same(existing,amount,request.rechargeType(),enterprise)||!Objects.equals(existing.paymentMethod,request.paymentMethod()==null?null:request.paymentMethod().name())))
          throw new IllegalStateException("request id conflicts");
        return convert(existing);
      }
      RechargeRules.validate(account.lockedType,request.rechargeType(),amount,!account.rechargeCompleted);
      var reusable=orders.selectList(new QueryWrapper<Order>().eq("user_id",request.userId()).eq("status","PENDING").eq("source","PROVIDER")
          .eq("amount4",amount).eq("recharge_type",request.rechargeType().name()).gt("expires_at",now.toEpochMilli()).orderByAsc("created_at","id"))
          .stream().filter(o->same(o,amount,request.rechargeType(),enterprise)&&Objects.equals(o.paymentMethod,request.paymentMethod()==null?null:request.paymentMethod().name())).findFirst().orElse(null);
      if(reusable==null){
        var domain=PaymentOrder.pending(UUID.randomUUID().toString(),request.userId(),amount,snapshot,request.rechargeType(),now);
        reusable=new Order();reusable.paymentMethod=request.paymentMethod()==null?null:request.paymentMethod().name();reusable.id=domain.id();reusable.userId=domain.userId();reusable.amount4=domain.amount4();
        reusable.rateSnapshot4=domain.rateSnapshot4();reusable.rechargeType=domain.rechargeType().name();reusable.enterpriseName=enterprise;
        reusable.createdAt=domain.createdAt().toEpochMilli();reusable.expiresAt=domain.expiresAt().toEpochMilli();reusable.status="PENDING";reusable.fulfilled=false;reusable.source="PROVIDER";
        orders.insert(reusable);if(cipher!=null)enqueue(reusable.id,"POLL");
      }
      var alias=new Request();alias.requestId=request.requestId();alias.userId=request.userId();alias.orderId=reusable.id;requests.insert(alias);
      return convert(reusable);
    });
  }
  private Account account(String owner){
    id(owner);accounts.ensure(owner);var account=accounts.lock(owner);
    if(account.pendingRequestId!=null)throw new IllegalStateException("ACCOUNT_CHANGE_PENDING");
    if(account.lockedType==null){
      String existing=existingIdentity.apply(owner);
      if(existing!=null){RechargeType.valueOf(existing);account.lockedType=existing;account.rechargeCompleted=true;accounts.updateById(account);}
    }
    return account;
  }
  public String accountType(String owner){return tx.execute(s->account(owner).lockedType);}
  public List<PaymentWorkflowStore.AccountIdentity> storedAccountIdentities(List<String> owners){
    if(owners==null||owners.isEmpty()||owners.size()>100||new HashSet<>(owners).size()!=owners.size())
      throw new IllegalArgumentException("1 to 100 unique user IDs required");
    owners.forEach(MybatisPaymentWorkflowStore::id);
    var known=new HashMap<String,String>();
    for(var row:accounts.selectByIds(owners)) {
      if(row.pendingRequestId!=null)throw new IllegalStateException("ACCOUNT_CHANGE_PENDING");
      known.put(row.userId,row.lockedType);
    }
    var apiSince=apiIdentityTimes(owners);
    return owners.stream().map(owner->new PaymentWorkflowStore.AccountIdentity(owner,known.get(owner),"API".equals(known.get(owner))?apiSince.get(owner):null)).toList();
  }
  public List<PaymentWorkflowStore.AccountIdentity> accountIdentities(List<String> owners){
    if(owners==null||owners.isEmpty()||owners.size()>100||new HashSet<>(owners).size()!=owners.size())
      throw new IllegalArgumentException("1 to 100 unique user IDs required");
    owners.forEach(MybatisPaymentWorkflowStore::id);
    var known=new HashMap<String,Account>();
    for(var row:accounts.selectByIds(owners)){
      if(row.pendingRequestId!=null)throw new IllegalStateException("ACCOUNT_CHANGE_PENDING");
      known.put(row.userId,row);
    }
    var apiSince=apiIdentityTimes(owners);
    // Read-only directory lookup: retain legacy Access identities without initializing or locking payment accounts.
    return owners.stream().map(owner->{
      var row=known.get(owner);
      String type=row==null||row.lockedType==null?existingIdentity.apply(owner):row.lockedType;
      if(type!=null)RechargeType.valueOf(type);
      return new PaymentWorkflowStore.AccountIdentity(owner,type,"API".equals(type)?apiSince.get(owner):null);
    }).toList();
  }
  private Map<String,Instant> apiIdentityTimes(List<String> owners){
    var apiSince=new HashMap<String,Instant>();
    for(var evidence:accounts.apiIdentityTimes(owners)) {
      var epoch=evidence.completedEpoch();
      Instant at=epoch==null?Instant.parse(evidence.paidAt()):Instant.ofEpochSecond(epoch.longValue(),epoch.remainder(BigDecimal.ONE).movePointRight(9).longValueExact());
      apiSince.merge(evidence.userId(),at,(a,b)->a.isBefore(b)?a:b);
    }
    return apiSince;
  }
  public boolean firstRecharge(String owner){return tx.execute(s->!account(owner).rechargeCompleted);}
  public PaymentWorkflowStore.AccountChange beginAccountChange(PaymentWorkflowStore.AccountChange command,boolean enterpriseAllowed){
    id(command.requestId());id(command.userId());id(command.operatorId());
    if(!Set.of("PERSONAL","ENTERPRISE","API").contains(command.accountType()))throw new IllegalArgumentException("invalid account type");
    if("API".equals(command.accountType())){
      if(!enterpriseAllowed)throw new PaymentWorkflow.Forbidden();
      if(!command.createKey())throw new IllegalArgumentException("API primary key required");
    }
    try{return tx.execute(s->{
      accounts.ensure(command.userId());var row=accounts.lock(command.userId());
      var prior=accounts.change(command.requestId());
      if(prior!=null){
        if(!prior.userId.equals(command.userId())||!prior.accountType.equals(command.accountType())||prior.createKey!=command.createKey()||!prior.operatorId.equals(command.operatorId()))throw new IllegalStateException("idempotency facts conflict");
        return changeView(prior);
      }
      if(row.pendingRequestId!=null)throw new IllegalStateException("ACCOUNT_CHANGE_PENDING");
      row=account(command.userId());
      if(!enterpriseAllowed&&("API".equals(row.lockedType)||"ENTERPRISE".equals(row.lockedType)||"ENTERPRISE".equals(command.accountType())))throw new PaymentWorkflow.Forbidden();
      if(!Objects.equals(row.lockedType,command.accountType())){
        var active=orders.selectCount(new QueryWrapper<Order>().eq("user_id",command.userId()).and(q->q
          .eq("status","REVIEW_PENDING").or().eq("status","PENDING").or().nested(n->n.eq("status","PAID").eq("fulfilled",false))));
        if(active>0)throw new IllegalStateException("ACCOUNT_HAS_PENDING_RECHARGE");
      }
      var change=new PaymentRows.AccountChange();change.requestId=command.requestId();change.userId=command.userId();change.accountType=command.accountType();change.createKey=command.createKey();change.operatorId=command.operatorId();change.completed=false;
      accounts.insertChange(change);row.pendingRequestId=change.requestId;accounts.updateById(row);return changeView(change);
    });}catch(org.springframework.dao.DuplicateKeyException conflict){throw new IllegalStateException("idempotency facts conflict");}
  }
  public void completeAccountChange(String request){
    id(request);tx.executeWithoutResult(s->{
      var change=accounts.change(request);if(change==null)throw new IllegalArgumentException("unknown account change");
      var row=accounts.lock(change.userId);
      change=accounts.change(request);if(change.completed)return;
      if(!request.equals(row.pendingRequestId))throw new IllegalStateException("account change conflict");
      accounts.finishChange(change.userId,request,change.accountType);accounts.completeChange(request);
    });
  }
  public List<PaymentWorkflowStore.AccountChange> pendingAccountChanges(){return accounts.pendingChanges().stream().map(MybatisPaymentWorkflowStore::changeView).toList();}
  private static PaymentWorkflowStore.AccountChange changeView(PaymentRows.AccountChange row){return new PaymentWorkflowStore.AccountChange(row.requestId,row.userId,row.accountType,row.createKey,row.operatorId,row.completed);}
  public List<RechargeOrder> history(String owner){
    if(owner!=null)id(owner);
    return orders.selectList(new QueryWrapper<Order>().eq(owner!=null,"user_id",owner).orderByDesc("created_at","id").last("LIMIT 100"))
      .stream().map(this::convert).toList();
  }
  public OrderPage adminOrders(int offset,int limit,Instant to){
    if(offset<0||limit<1||limit>100||to==null)throw new IllegalArgumentException("invalid order page");
    long cutoff=to.toEpochMilli();
    long total=orders.selectCount(new QueryWrapper<Order>().le("created_at",cutoff));
    var items=orders.selectList(new QueryWrapper<Order>().le("created_at",cutoff).orderByDesc("created_at","id").last("LIMIT "+limit+" OFFSET "+offset))
      .stream().map(this::convert).toList();
    return new OrderPage(items,total,offset,limit);
  }
  public RechargeOrder corporate(PaymentWorkflow.CorporateRecharge request,BigDecimal rate,Instant now){
    Objects.requireNonNull(request);id(request.requestId());id(request.userId());
    var proof=Objects.requireNonNull(request.proof());
    BigDecimal amount=RechargeRules.yuan(request.amount4()),snapshot=money(rate);money(amount.multiply(snapshot));
    String remark=request.remark()==null?"":request.remark();
    if(remark.length()>200)throw new IllegalArgumentException("remark exceeds 200 characters");
    try{return tx.execute(s->{
      var account=account(request.userId());var prior=requests.selectById(request.requestId());
      if(prior!=null){
        var existing=required(prior.orderId);var stored=orders.proof(existing.id);
        if(!prior.userId.equals(request.userId())||!"CORPORATE".equals(existing.source)||!same(existing,amount,RechargeType.API,null)
            ||!Objects.equals(existing.remark,remark)||stored==null||!Objects.equals(stored.fileName,proof.fileName())
            ||!Objects.equals(stored.contentType,proof.contentType())||!Arrays.equals(stored.content,proof.content()))
          throw new IllegalStateException("request id conflicts");
        return convert(existing);
      }
      RechargeRules.validate(account.lockedType,RechargeType.API,amount,!account.rechargeCompleted);
      var row=new Order();row.id=UUID.randomUUID().toString();row.userId=request.userId();row.amount4=amount;row.rateSnapshot4=snapshot;
      row.rechargeType="API";row.createdAt=now.toEpochMilli();row.expiresAt=now.plusSeconds(900).toEpochMilli();
      row.status="REVIEW_PENDING";row.fulfilled=false;row.source="CORPORATE";row.remark=remark;row.submittedBy=request.userId();orders.insert(row);
      var receipt=new PaymentRows.Proof();receipt.orderId=row.id;receipt.fileName=proof.fileName();receipt.contentType=proof.contentType();receipt.sizeBytes=proof.content().length;receipt.content=proof.content();orders.insertProof(receipt);
      var alias=new Request();alias.requestId=request.requestId();alias.userId=request.userId();alias.orderId=row.id;requests.insert(alias);
      return convert(row);
    });}catch(org.springframework.dao.DuplicateKeyException conflict){throw new IllegalStateException("request id conflicts");}
  }
  public TransferPage corporateHistory(int page,int size){
    if(page<0||page>1000000||size<1||size>100)throw new IllegalArgumentException("invalid page");
    long total=orders.selectCount(new QueryWrapper<Order>().eq("source","CORPORATE"));
    var items=orders.selectList(new QueryWrapper<Order>().eq("source","CORPORATE").orderByDesc("created_at","id").last("LIMIT "+size+" OFFSET "+((long)page*size)))
      .stream().map(row->{var proof=orders.proofMetadata(row.id);return new Transfer(convert(row),proof.fileName,proof.contentType,proof.sizeBytes);}).toList();
    return new TransferPage(items,total,page,size);
  }
  public TransferProof transferProof(String id){
    id(id);var proof=orders.proof(id);if(proof==null)throw new IllegalArgumentException("unknown receipt");
    return new TransferProof(proof.fileName,proof.contentType,proof.content);
  }
  public RechargeOrder manual(PaymentWorkflow.ManualRecharge request,BigDecimal rate,Instant now,boolean enterpriseAllowed){
    Objects.requireNonNull(request);id(request.requestId());id(request.userId());id(request.submittedBy());
    if(request.accountType()==null)throw new IllegalArgumentException("account type required");
    BigDecimal amount=RechargeRules.yuan(request.amount4()),snapshot=money(rate);money(amount.multiply(snapshot));
    String enterprise=request.enterpriseName()==null?null:request.enterpriseName().strip();
    if(enterprise!=null&&enterprise.length()>200)throw new IllegalArgumentException("enterprise name too long");
    if(request.accountType()!=RechargeType.ENTERPRISE&&enterprise!=null&&!enterprise.isEmpty())throw new IllegalArgumentException("enterprise name only for enterprise account");
    if(request.remark()==null||request.remark().isBlank()||request.remark().length()>1000)throw new IllegalArgumentException("manual review remark required");
    return tx.execute(s->{
      var account=account(request.userId());var prior=requests.selectById(request.requestId());
      if(prior!=null){
        var existing=required(prior.orderId);
        if(!prior.userId.equals(request.userId())||!"MANUAL".equals(existing.source)||!same(existing,amount,request.accountType(),enterprise)
            ||!Objects.equals(existing.remark,request.remark())||!Objects.equals(existing.submittedBy,request.submittedBy()))throw new IllegalStateException("request id conflicts");
        return convert(existing);
      }
      if(!enterpriseAllowed&&((!request.accountType().name().equals(account.lockedType)&&(request.accountType()==RechargeType.ENTERPRISE||"ENTERPRISE".equals(account.lockedType)))||(request.accountType()==RechargeType.API&&!"API".equals(account.lockedType))))throw new PaymentWorkflow.Forbidden();
      RechargeRules.validateAccountType(account.lockedType,request.accountType());
      var row=new Order();row.id=UUID.randomUUID().toString();row.userId=request.userId();row.amount4=amount;row.rateSnapshot4=snapshot;
      row.rechargeType=request.accountType().name();row.enterpriseName=enterprise;row.createdAt=now.toEpochMilli();row.expiresAt=now.plusSeconds(900).toEpochMilli();
      row.status="REVIEW_PENDING";row.fulfilled=false;row.source="MANUAL";row.remark=request.remark();row.submittedBy=request.submittedBy();orders.insert(row);
      var alias=new Request();alias.requestId=request.requestId();alias.userId=request.userId();alias.orderId=row.id;requests.insert(alias);return convert(row);
    });
  }
  public RechargeOrder confirmManual(String id,String reviewer,Instant now,boolean enterpriseAllowed){
    id(id);id(reviewer);
    return tx.execute(s->{
      var preview=orders.selectById(id);if(preview==null)throw new IllegalArgumentException("unknown order");
      var account=account(preview.userId);var row=required(id);
      if(!Set.of("MANUAL","CORPORATE").contains(row.source))throw new IllegalStateException("provider order cannot receive manual confirmation");
      if("PAID".equals(row.status))return convert(row);
      if(!"REVIEW_PENDING".equals(row.status))throw new IllegalStateException("review is not pending");
      if(!enterpriseAllowed&&((!row.rechargeType.equals(account.lockedType)&&("ENTERPRISE".equals(row.rechargeType)||"ENTERPRISE".equals(account.lockedType)))||("API".equals(row.rechargeType)&&!"API".equals(account.lockedType))))throw new PaymentWorkflow.Forbidden();
      RechargeRules.validateAccountType(account.lockedType,RechargeType.valueOf(row.rechargeType));
      account.lockedType=row.rechargeType;account.rechargeCompleted=true;accounts.updateById(account);
      row.status="PAID";row.paidAt=now.toString();row.confirmedBy=reviewer;orders.updateById(row);enqueue(row.id,"FULFILL");return convert(row);
    });
  }
  public RechargeOrder rejectManual(String id,String reviewer,Instant now){
    id(id);id(reviewer);
    return tx.execute(s->{
      var preview=orders.selectById(id);if(preview==null)throw new IllegalArgumentException("unknown order");
      // Match confirmation's lock order without changing account identity or first-recharge state.
      if(accounts.lock(preview.userId)==null)throw new IllegalStateException("missing payment account");
      var row=required(id);
      if(!Set.of("MANUAL","CORPORATE").contains(row.source))throw new IllegalStateException("provider order cannot receive manual rejection");
      if("REJECTED".equals(row.status))return convert(row);
      if(!"REVIEW_PENDING".equals(row.status))throw new IllegalStateException("review is not pending");
      row.status="REJECTED";row.rejectedBy=reviewer;row.rejectedAt=now.toString();orders.updateById(row);
      return convert(row);
    });
  }
  public Optional<RechargeOrder> find(String id){id(id);return Optional.ofNullable(orders.selectById(id)).map(this::convert);}
  public RechargeOrder checkout(String id,PaymentProvider.Checkout checkout){
    if(checkout.checkoutUrl()!=null&&checkout.checkoutUrl().length()>2048)throw new IllegalArgumentException("checkout URL too long");
    return tx.execute(s->{
      var row=required(id);
      if(!"PROVIDER".equals(row.source))throw new IllegalStateException("manual order has no provider checkout");
      if(row.providerOrderId!=null&&!row.providerOrderId.equals(checkout.providerOrderId()))throw new IllegalStateException("provider order changed");
      if(row.providerOrderId==null||(row.checkoutUrl==null&&checkout.checkoutUrl()!=null)){row.providerOrderId=checkout.providerOrderId();row.checkoutUrl=cipher==null?checkout.checkoutUrl():cipher.encrypt("checkout:"+id,checkout.checkoutUrl());orders.updateById(row);}
      return convert(row);
    });
  }
  public RechargeOrder confirm(PaymentProvider.VerifiedPayment paid,Instant receivedAt){
    Objects.requireNonNull(paid);id(paid.orderId());id(paid.transactionId());
    if(!"CNY".equals(paid.currency())||paid.amount4()==null||paid.paidAt()==null||paid.paidAt().isAfter(receivedAt))
      throw new IllegalArgumentException("invalid verified payment");
    return tx.execute(s->{
      var preview=orders.selectById(paid.orderId());if(preview==null)throw new IllegalArgumentException("unknown order");
      var account=account(preview.userId);var row=required(paid.orderId());
      if(!"PROVIDER".equals(row.source))throw new IllegalStateException("manual order cannot receive provider confirmation");
      if(row.amount4.compareTo(paid.amount4())!=0)throw new IllegalArgumentException("payment amount mismatch");
      if(row.transactionId!=null){
        if(!row.transactionId.equals(paid.transactionId())||!Instant.parse(row.paidAt).equals(paid.paidAt()))throw new IllegalStateException("payment confirmation conflicts");
        return convert(row);
      }
      var confirmed=convert(row).domain().confirmAt(paid.transactionId(),paid.paidAt());
      row.status=confirmed.status().name();row.transactionId=paid.transactionId();row.paidAt=paid.paidAt().toString();
      if(row.status.equals("PAID")){
        if(!RechargeRules.allowed(account.lockedType,RechargeType.valueOf(row.rechargeType),row.amount4,!account.rechargeCompleted))row.status="REFUND_PENDING";
        else {account.lockedType=row.rechargeType;account.rechargeCompleted=true;accounts.updateById(account);}
      }
      orders.updateById(row);enqueue(row.id,row.status.equals("PAID")?"FULFILL":"REFUND");
      return convert(row);
    });
  }
  public RechargeOrder requestRefund(String id,String owner){
    return tx.execute(s->{
      var row=required(id);
      if(!row.userId.equals(owner))throw new PaymentWorkflow.Forbidden();
      if(!"PROVIDER".equals(row.source))throw new IllegalStateException("manual order requires separate adjustment audit");
      if(row.status.equals("REFUND_PENDING")||row.status.equals("REFUNDED"))return convert(row);
      if(!row.status.equals("PAID")||!row.fulfilled)throw new IllegalStateException("only fulfilled paid orders can be refunded");
      row.status="REFUND_PENDING";orders.updateById(row);enqueue(row.id,"REFUND");return convert(row);
    });
  }
  public void expire(Instant now){orders.expire(now.toEpochMilli());}
  public List<String> pending(Instant now){
    return work.selectList(new QueryWrapper<PaymentRows.Work>().eq("completed",false).le("next_attempt_at",now.toEpochMilli())
        .le("lease_until",now.toEpochMilli()).orderByAsc("next_attempt_at","order_id").last("LIMIT 100")).stream().map(r->r.orderId).toList();
  }
  public Optional<PaymentWorkflowStore.Work> claim(String id,Instant now){
    String token=UUID.randomUUID().toString();
    return tx.execute(s->{
      if(work.claim(id,token,now.toEpochMilli(),now.plusSeconds(60).toEpochMilli())!=1)return Optional.empty();
      var row=work.selectById(id);
      return Optional.of(new PaymentWorkflowStore.Work(convert(orders.selectById(id)),row.stage,token));
    });
  }
  public void complete(PaymentWorkflowStore.Work claimed,String reference){
    tx.executeWithoutResult(s->{
      var account="FULFILL".equals(claimed.stage())?account(claimed.order().userId()):null;
      var pending=work.lock(claimed.order().id());if(!current(pending,claimed))return;
      var row=required(pending.orderId);
      if(pending.stage.equals("POLL")){if(row.status.equals("PENDING"))row.status="EXPIRED";}
      else if(pending.stage.equals("FULFILL")){
        // Access applies each paid order once; a retry may return a newer personal/enterprise identity.
        String applied=existingIdentity.apply(row.userId);
        account.lockedType=applied==null?row.rechargeType:applied;accounts.updateById(account);
        row.fulfilled=true;row.keyId=reference;work.enqueuePromotion(row.id,1,row.paidAt);
      }
      else{
        row.status="REFUNDED";row.refundReference=reference;row.refundedAt=Instant.now().toString();
        if(row.fulfilled)work.enqueuePromotion(row.id,2,row.refundedAt);
      }
      orders.updateById(row);pending.completed=true;work.updateById(pending);
    });
  }
  public List<PromotionDelivery> pendingPromotions(Instant now){
    return work.pendingPromotions(now.toEpochMilli()).stream().map(r->new PromotionDelivery(
        convert(orders.selectById(r.orderId)),r.version,Instant.parse(r.occurredAt))).toList();
  }
  public void completePromotion(PromotionDelivery delivery){work.completePromotion(delivery.order().id(),delivery.version());}
  public void retryPromotion(PromotionDelivery delivery,Instant now){work.retryPromotion(delivery.order().id(),delivery.version(),now.plusSeconds(30).toEpochMilli());}
  public void retry(PaymentWorkflowStore.Work claimed,Instant now){
    tx.executeWithoutResult(s->{
      var row=work.lock(claimed.order().id());if(!current(row,claimed))return;
      row.leaseUntil=0L;row.nextAttemptAt=now.plusSeconds(Math.min(300,5L*(row.attempts+1))).toEpochMilli();
      row.attempts++;row.lastError="REMOTE_OPERATION_RETRY_REQUIRED";work.updateById(row);
    });
  }
  private void enqueue(String id,String stage){
    var row=work.selectById(id);
    boolean insert=row==null;if(insert){row=new PaymentRows.Work();row.orderId=id;}
    row.stage=stage;row.leaseToken=UUID.randomUUID().toString();row.completed=false;row.attempts=0;row.leaseUntil=0L;row.nextAttemptAt=0L;
    if(insert)work.insert(row);else work.updateById(row);
  }
  private static boolean current(PaymentRows.Work row,PaymentWorkflowStore.Work claimed){return row!=null&&!row.completed&&Objects.equals(row.leaseToken,claimed.leaseToken());}
  private Order required(String id){id(id);var row=orders.lock(id);if(row==null)throw new IllegalArgumentException("unknown order");return row;}
  private static boolean same(Order row,BigDecimal amount,RechargeType type,String enterprise){
    return row.amount4.compareTo(amount)==0&&row.rechargeType.equals(type.name())&&Objects.equals(row.enterpriseName,enterprise);
  }
  private RechargeOrder convert(Order r){
    return new RechargeOrder(r.id,r.userId,r.amount4,r.rateSnapshot4,RechargeType.valueOf(r.rechargeType),r.enterpriseName,
      Instant.ofEpochMilli(r.createdAt),Instant.ofEpochMilli(r.expiresAt),r.status,r.transactionId,r.paidAt==null?null:Instant.parse(r.paidAt),
      r.providerOrderId,checkoutValue(r),r.fulfilled,r.keyId,r.refundReference,r.source,r.remark,r.submittedBy,r.confirmedBy,r.paymentMethod==null?null:com.xzwl.cloud.payment.domain.order.PaymentMethod.valueOf(r.paymentMethod),r.rejectedBy,r.rejectedAt==null?null:Instant.parse(r.rejectedAt),r.refundedAt==null?null:Instant.parse(r.refundedAt));
  }
  private String checkoutValue(Order r){
    if(r.checkoutUrl!=null&&r.checkoutUrl.startsWith("sqb1:")){
      if(cipher==null)throw new IllegalStateException("payment encryption key unavailable");
      return cipher.decrypt("checkout:"+r.id,r.checkoutUrl);
    }
    return r.checkoutUrl;
  }
  private static BigDecimal money(BigDecimal value){
    if(value==null||value.compareTo(new BigDecimal("0.0001"))<0||value.compareTo(BigDecimal.TEN.pow(34))>=0)throw new IllegalArgumentException("invalid amount");
    var result=value.setScale(4,RoundingMode.DOWN);if(result.signum()<=0)throw new IllegalArgumentException("amount below precision");return result;
  }
  private static void id(String id){if(id==null||!id.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("invalid identifier");}
}
