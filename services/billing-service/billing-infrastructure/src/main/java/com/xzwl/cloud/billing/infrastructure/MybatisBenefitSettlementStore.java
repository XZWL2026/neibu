package com.xzwl.cloud.billing.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xzwl.cloud.billing.application.ledger.*;
import com.xzwl.cloud.billing.infrastructure.persistence.*;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Each wallet lock serializes settlement/refund facts and reward ledger writes. */
public final class MybatisBenefitSettlementStore implements BenefitSettlementStore {
  private final BillingDatabase db;
  private final MediaBilling.Keys keys;
  public MybatisBenefitSettlementStore(BillingDatabase db,MediaBilling.Keys keys) {this.db=db;this.keys=keys;}
  public void settle(Settlement raw) {
    id(raw.taskId());id(raw.ownerId());Objects.requireNonNull(raw.occurredAt());
    BigDecimal charged=decimal(raw.chargedPoints4()),refunded=decimal(raw.refundedPoints4());
    if(refunded.compareTo(charged)>0 || !Set.of("SUCCEEDED","PARTIAL_SUCCESS","FAILED","TIMED_OUT").contains(raw.status())
        || (raw.status().equals("SUCCEEDED") && refunded.signum()!=0)
        || (Set.of("FAILED","TIMED_OUT").contains(raw.status()) && refunded.compareTo(charged)!=0)
        || (raw.status().equals("PARTIAL_SUCCESS") && (refunded.signum()==0 || refunded.compareTo(charged)==0)))
      throw new IllegalArgumentException("invalid terminal amounts");
    String facts=encode(new Settlement(raw.taskId(),raw.ownerId(),raw.status(),text(charged),text(refunded),raw.occurredAt()));
    db.transaction(()->{
      var charge=db.mapper(MediaChargeMapper.class).selectById(raw.taskId());
      if(charge==null || !charge.ownerId.equals(raw.ownerId()) || charged.compareTo(charge.points4)!=0)
        throw new IllegalArgumentException("settlement charge mismatch");
      new MybatisLedgerStore(db).lock(charge.keyId);
      var mapper=db.mapper(BenefitSettlementMapper.class);
      var prior=mapper.settlement(raw.taskId());
      if(prior!=null) {same(prior.facts,facts);return null;}
      var rows=db.mapper(BillingMappers.Allocations.class).selectList(new QueryWrapper<BillingRows.Allocation>()
          .eq("wallet_id",charge.keyId).eq("operation_id",raw.taskId()).eq("kind","CONSUME").orderByAsc("id"));
      BigDecimal total=BigDecimal.ZERO,baseRefund=BigDecimal.ZERO;
      for(var row:rows) {
        total=total.add(row.points4);
        BigDecimal extra=mapper.refunds(row.id).stream().filter(r->!r.refundId.equals(raw.taskId()+"-refund"))
            .map(r->r.points4).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal initialRefund=row.refundedPoints4.subtract(extra);
        if(initialRefund.signum()<0)throw new IllegalStateException("refund facts mismatch");
        baseRefund=baseRefund.add(initialRefund);
      }
      if(total.compareTo(charged)!=0 || baseRefund.compareTo(refunded)!=0)throw new IllegalStateException("terminal allocation totals mismatch");
      mapper.settlementInsert(raw.taskId(),charge.keyId,facts);
      for(var row:rows) {
        var lot=db.mapper(BillingMappers.Lots.class).selectById(row.lotId);
        if(lot==null)throw new IllegalStateException("missing funding lot");
        if(!"REAL_RECHARGE".equals(lot.lotType) || !Boolean.TRUE.equals(lot.benefitEligible))continue;
        var refunds=mapper.refunds(row.id);
        BigDecimal extra=refunds.stream().filter(r->!r.refundId.equals(raw.taskId()+"-refund"))
            .map(r->r.points4).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal successful=row.points4.subtract(row.refundedPoints4).add(extra);
        if(successful.signum()==0)continue;
        if(!lot.lotId.startsWith("recharge:"))throw new IllegalStateException("invalid recharge lot");
        var recharge=db.mapper(BillingMappers.Recharges.class).selectById(lot.lotId.substring("recharge:".length()));
        if(recharge==null || !recharge.walletId.equals(charge.keyId) || !recharge.userId.equals(raw.ownerId())
            || recharge.points4.compareTo(lot.originalPoints4)!=0)throw new IllegalStateException("funding source mismatch");
        var event=new Consumption("settle:"+row.id,"SETTLED",raw.taskId(),row.id,lot.lotId,recharge.orderId,
            recharge.walletId,recharge.userId,text(recharge.amount4),text(recharge.points4),text(successful),"0.0000",raw.occurredAt());
        var stored=new BenefitSettlementMapper.AllocationRow();stored.allocationId=row.id;stored.settlementId=raw.taskId();
        stored.successfulPoints4=successful;stored.payload=encode(event);mapper.allocationInsert(stored);mapper.enqueue(event.eventId(),stored.payload);
        for(var refund:refunds)if(!refund.refundId.equals(raw.taskId()+"-refund"))emitReverse(db,refund);
      }
      return null;
    });
  }

  static BigDecimal refundReplayAmount(BillingDatabase db,String wallet,String refund,BigDecimal requested) {
    var receipt=db.mapper(BenefitSettlementMapper.class).refundReceipt(wallet,refund);
    if(receipt==null)return requested;
    if(receipt.requestedPoints4.compareTo(requested)!=0)throw new IllegalStateException("refund facts conflict");
    return receipt.creditedPoints4;
  }
  static BigDecimal offsetRewardRefund(BillingDatabase db,String lotId,BigDecimal restored) {
    var mapper=db.mapper(BenefitSettlementMapper.class);var entitlement=mapper.entitlementForLot(lotId);
    if(entitlement==null)return BigDecimal.ZERO;
    var account=mapper.lockAccount(entitlement.beneficiaryUserId);
    if(account==null)throw new IllegalStateException("reward account missing");
    BigDecimal offset=restored.min(account.debtPoints4);
    if(offset.signum()>0)mapper.debt(entitlement.beneficiaryUserId,account.debtPoints4.subtract(offset));
    return offset;
  }
  static void refundReceipt(BillingDatabase db,String wallet,String refund,BigDecimal requested,BigDecimal credited) {
    if(requested.compareTo(credited)!=0)db.mapper(BenefitSettlementMapper.class).refundReceiptInsert(wallet,refund,requested,credited);
  }
  /** Called inside the original refund wallet transaction, including before terminal delivery. */
  static void recordRefund(BillingDatabase db,String refundId,String allocationId,BigDecimal points,Instant at) {
    var row=new BenefitSettlementMapper.RefundRow();row.refundId=refundId;row.allocationId=allocationId;row.points4=points;row.occurredAt=at.toString();
    db.mapper(BenefitSettlementMapper.class).refundInsert(row);
    emitReverse(db,row);
  }
  private static void emitReverse(BillingDatabase db,BenefitSettlementMapper.RefundRow refund) {
    var mapper=db.mapper(BenefitSettlementMapper.class);var allocation=mapper.allocation(refund.allocationId);
    if(allocation==null)return;
    Consumption original=decode(allocation.payload,Consumption.class);
    String eventId="reverse:"+UUID.nameUUIDFromBytes((refund.refundId+"\n"+refund.allocationId).getBytes(StandardCharsets.UTF_8));
    var event=new Consumption(eventId,"REVERSED",original.settlementId(),original.allocationId(),original.fundingLotId(),
        original.orderId(),original.walletId(),original.walletOwnerUserId(),original.originalPaidAmount4(),original.originalPoints4(),
        original.successfulPoints4(),text(refund.points4),Instant.parse(refund.occurredAt));
    if(mapper.reverse(refund.allocationId,refund.points4)!=1)throw new IllegalStateException("reversal exceeds successful points");
    mapper.enqueue(eventId,encode(event));
  }

  public String debtPoints(String ownerId) {
    id(ownerId);var account=db.mapper(BenefitSettlementMapper.class).account(ownerId);
    return account==null?"0.0000":text(account.debtPoints4);
  }
  public Receipt reward(Reward raw) {
    id(raw.eventId());id(raw.rewardEntitlementId());id(raw.beneficiaryUserId());id(raw.sourceSettlementId());id(raw.sourceAllocationId());
    Objects.requireNonNull(raw.occurredAt());BigDecimal points=decimal(raw.rewardPoints4());
    if(points.signum()==0 || !Set.of("GRANT","REVERSE").contains(raw.kind()))throw new IllegalArgumentException("invalid reward");
    String facts=encode(new Reward(raw.eventId(),raw.kind(),raw.rewardEntitlementId(),raw.beneficiaryUserId(),raw.sourceSettlementId(),raw.sourceAllocationId(),text(points),raw.occurredAt()));
    var mapper=db.mapper(BenefitSettlementMapper.class);var previous=mapper.rewardEvent(raw.eventId());
    if(previous!=null){same(previous.facts,facts);return decode(previous.receipt,Receipt.class);}
    String wallet;
    var existingAccount=mapper.account(raw.beneficiaryUserId());
    if(existingAccount!=null)wallet=existingAccount.walletId;
    else {
      wallet=mapper.originalWallet(raw.beneficiaryUserId());
      if(wallet==null) {
        var key=keys.forOwner(raw.beneficiaryUserId());
        if(key==null || !raw.beneficiaryUserId().equals(key.ownerId()))throw new IllegalStateException("reward wallet unavailable");
        wallet=key.keyId();
      }
    }
    id(wallet);String resolved=wallet;
    return db.transaction(()->{
      // Wallet first: consumption refunds use the same lock order.
      var wallets=db.mapper(BillingMappers.Wallets.class);wallets.ensure(resolved);wallets.lock(resolved);
      mapper.ensureAccount(raw.beneficiaryUserId(),resolved);var account=mapper.lockAccount(raw.beneficiaryUserId());
      if(!resolved.equals(account.walletId))throw new IllegalStateException("reward wallet changed concurrently");
      var prior=mapper.rewardEvent(raw.eventId());if(prior!=null){same(prior.facts,facts);return decode(prior.receipt,Receipt.class);}
      var entitlement=mapper.entitlement(raw.rewardEntitlementId());
      BigDecimal credited=BigDecimal.ZERO,recovered=BigDecimal.ZERO,debt=account.debtPoints4;
      var ledger=new MybatisLedgerStore(db);var lots=db.mapper(BillingMappers.Lots.class);
      if(raw.kind().equals("GRANT")) {
        if(entitlement!=null)throw new IllegalStateException("entitlement already granted");
        BigDecimal offset=points.min(debt);credited=points.subtract(offset);debt=debt.subtract(offset);
        entitlement=new BenefitSettlementMapper.Entitlement();entitlement.entitlementId=raw.rewardEntitlementId();
        entitlement.beneficiaryUserId=raw.beneficiaryUserId();entitlement.settlementId=raw.sourceSettlementId();entitlement.allocationId=raw.sourceAllocationId();
        entitlement.originalPoints4=points;entitlement.lotId="reward:"+raw.rewardEntitlementId();mapper.entitlementInsert(entitlement);
        if(credited.signum()>0) {
          var lot=new BillingRows.Lot();lot.lotId=entitlement.lotId;lot.walletId=resolved;lot.lotType="REFERRAL_REWARD";
          lot.originalPoints4=credited;lot.remainingPoints4=credited;lot.creditedAt=raw.occurredAt().toString();lot.benefitEligible=false;lots.insert(lot);
        }
        ledger.changeAndRecord(resolved,"REFERRAL_REWARD",raw.eventId(),raw.rewardEntitlementId(),credited,raw.occurredAt());
      } else {
        if(entitlement==null)throw new IllegalStateException("original reward pending");
        if(!entitlement.beneficiaryUserId.equals(raw.beneficiaryUserId()) || !entitlement.settlementId.equals(raw.sourceSettlementId())
            || !entitlement.allocationId.equals(raw.sourceAllocationId()))throw new IllegalStateException("reward source mismatch");
        if(mapper.entitlementReverse(raw.rewardEntitlementId(),points)!=1)throw new IllegalArgumentException("reward reversal exceeds original");
        var lot=lots.selectById(entitlement.lotId);recovered=lot==null?BigDecimal.ZERO:points.min(lot.remainingPoints4);
        debt=debt.add(points.subtract(recovered));
        if(recovered.signum()>0) {
          if(lots.change(entitlement.lotId,recovered.negate())!=1)throw new IllegalStateException("reward lot changed");
        }
        ledger.changeAndRecord(resolved,"REFERRAL_REVERSED",raw.eventId(),raw.rewardEntitlementId(),recovered.negate(),raw.occurredAt());
      }
      mapper.debt(raw.beneficiaryUserId(),debt);
      var receipt=new Receipt(resolved,text(credited),text(recovered),text(debt));mapper.rewardEventInsert(raw.eventId(),facts,encode(receipt));return receipt;
    });
  }
  public List<Consumption> pending(Instant now) {return db.mapper(BenefitSettlementMapper.class).pending(now.toEpochMilli()).stream().map(r->decode(r.payload,Consumption.class)).toList();}
  public void acknowledged(String id) {db.mapper(BenefitSettlementMapper.class).acknowledge(id);}
  public void retry(String id,Instant next) {db.mapper(BenefitSettlementMapper.class).retry(id,next.toEpochMilli());}
  // Flat, validated scalar records need no JSON dependency in this persistence module.
  private static String encode(Object value) {
    try {
      var values=new ArrayList<String>();
      for(var field:value.getClass().getRecordComponents()) {
        String text=Objects.requireNonNull(field.getAccessor().invoke(value)).toString();
        if(text.contains("\n"))throw new IllegalArgumentException("invalid persisted scalar");
        values.add(text);
      }
      return String.join("\n",values);
    } catch(ReflectiveOperationException failure){throw new IllegalStateException("cannot encode billing fact",failure);}
  }
  private static <T>T decode(String value,Class<T> type) {
    try {
      var fields=type.getRecordComponents();var values=value.split("\n",-1);
      if(fields.length!=values.length)throw new IllegalStateException("invalid billing fact");
      var types=new Class<?>[fields.length];var arguments=new Object[fields.length];
      for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();arguments[i]=types[i]==Instant.class?Instant.parse(values[i]):values[i];}
      return type.getDeclaredConstructor(types).newInstance(arguments);
    }catch(ReflectiveOperationException failure){throw new IllegalStateException("cannot decode billing fact",failure);}
  }
  private static void same(String left,String right) {if(!left.equals(right))throw new IllegalStateException("event facts conflict");}
  private static void id(String value) {MybatisLedgerStore.id(value);}
  private static String text(BigDecimal value) {return value.setScale(4).toPlainString();}
  private static BigDecimal decimal(String value) {
    if(value==null || !value.matches("(0|[1-9][0-9]*)(\\.[0-9]{1,4})?"))throw new IllegalArgumentException("invalid decimal string");
    var result=new BigDecimal(value).setScale(4);if(result.precision()>38)throw new IllegalArgumentException("decimal out of range");return result;
  }
}
