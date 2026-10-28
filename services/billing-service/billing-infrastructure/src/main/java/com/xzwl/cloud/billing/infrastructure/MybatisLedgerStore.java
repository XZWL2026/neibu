package com.xzwl.cloud.billing.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xzwl.cloud.billing.application.ledger.BillingLedgerStore;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.wallet.WalletBalance;
import com.xzwl.cloud.billing.infrastructure.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.xzwl.cloud.billing.infrastructure.persistence.BillingRows.*;

public final class MybatisLedgerStore implements BillingLedgerStore {
  private final BillingDatabase db;
  public MybatisLedgerStore(BillingDatabase db) { this.db=db; }
  public WalletBalance balance(String walletId) {
    id(walletId);
    var row=db.mapper(BillingMappers.Wallets.class).selectById(walletId);
    return row==null ? WalletBalance.zero(walletId) : balance(row);
  }
  public static WalletBalance balance(Wallet row) {
    return new WalletBalance(row.walletId,Decimal4.of(row.availablePoints4),row.version);
  }
  public WalletBalance consume(String walletId,String operationId,Decimal4 points) {
    id(walletId); id(operationId); positive(points);
    return db.transaction(() -> {
      var wallet=lock(walletId);
      var prior=find(walletId,"CONSUME",operationId);
      if(prior!=null) return replay(prior,operationId,points.asBigDecimal().negate());
      if(wallet.availablePoints4.compareTo(points.asBigDecimal())<0) throw new IllegalArgumentException("insufficient balance");
      allocate(walletId,"CONSUME",operationId,points.asBigDecimal());
      return changeAndRecord(walletId,"CONSUME",operationId,operationId,points.asBigDecimal().negate(),Instant.now());
    });
  }
  private void allocate(String walletId,String kind,String operationId,BigDecimal points) {
      var lots=db.mapper(BillingMappers.Lots.class);
      var allocations=db.mapper(BillingMappers.Allocations.class);
      BigDecimal left=points;
      var ordered=new ArrayList<>(lots.selectList(new QueryWrapper<Lot>().eq("wallet_id",walletId).gt("remaining_points4",0)));
      ordered.sort(Comparator.<Lot,Instant>comparing(l -> Instant.parse(l.creditedAt)).thenComparing(l -> l.lotId));
      for(var lot:ordered) {
        if(left.signum()==0) break;
        BigDecimal used=left.min(lot.remainingPoints4);
        if(lots.change(lot.lotId,used.negate())!=1) throw new IllegalStateException("funding balance changed");
        var allocation=new Allocation(); allocation.id=UUID.randomUUID().toString();
        allocation.kind=kind; allocation.walletId=walletId; allocation.operationId=operationId; allocation.lotId=lot.lotId;
        allocation.points4=used; allocation.refundedPoints4=BigDecimal.ZERO;
        allocations.insert(allocation); left=left.subtract(used);
      }
      if(left.signum()!=0) throw new IllegalStateException("wallet funding does not match balance");
  }
  public WalletBalance debit(Debit command) {
    id(command.requestId());id(command.keyId());id(command.userId());id(command.operatorId());
    BigDecimal amount=command.amount4();
    if(amount==null || amount.signum()<=0 || amount.compareTo(BigDecimal.TEN.pow(34))>=0
        || amount.stripTrailingZeros().scale()>2) throw new IllegalArgumentException("positive whole cents required");
    if(command.remark()==null || command.remark().isBlank() || command.remark().length()>2000)
      throw new IllegalArgumentException("remark required");
    return db.transaction(() -> {
      var records=db.mapper(BillingMappers.AdminDebits.class);
      records.lockRequest(command.requestId());
      var prior=records.selectById(command.requestId());
      if(prior!=null) {
        if(!prior.keyId.equals(command.keyId()) || !prior.userId.equals(command.userId())
            || !prior.operatorId.equals(command.operatorId()) || prior.amount4.compareTo(amount)!=0
            || !prior.remark.equals(command.remark())) throw new IllegalStateException("idempotency facts conflict");
        return new WalletBalance(prior.keyId,Decimal4.of(prior.balanceAfter4),prior.balanceVersion);
      }
      var wallet=lock(command.keyId());
      var rate=new MybatisRechargeRateStore(db).load().pointsPerYuan4();
      var points=Decimal4.of(amount).multiply(rate);
      positive(points);
      if(wallet.availablePoints4.compareTo(points.asBigDecimal())<0) throw new IllegalArgumentException("insufficient balance");
      allocate(command.keyId(),"ADMIN_DEBIT",command.requestId(),points.asBigDecimal());
      var at=Instant.now();
      var balance=changeAndRecord(command.keyId(),"ADMIN_DEBIT",command.requestId(),command.requestId(),points.asBigDecimal().negate(),at);
      var row=new AdminDebit();row.requestId=command.requestId();row.keyId=command.keyId();row.userId=command.userId();
      row.operatorId=command.operatorId();row.amount4=amount.setScale(4);row.rateSnapshot4=rate.asBigDecimal();
      row.points4=points.asBigDecimal();row.remark=command.remark();row.recordedAt=at.toString();
      row.balanceAfter4=balance.availablePoints4().asBigDecimal();row.balanceVersion=balance.version();
      records.insert(row);
      return balance;
    });
  }
  public List<Adjustment> adjustments(String keyId) {
    id(keyId);
    return db.mapper(BillingMappers.AdminDebits.class).selectList(new QueryWrapper<AdminDebit>()
        .eq("key_id",keyId).orderByDesc("balance_version").last("LIMIT 100")).stream()
        .map(row -> new Adjustment(row.requestId,"ADMIN_DEBIT",row.operatorId,row.userId,row.keyId,
            row.amount4.toPlainString(),row.rateSnapshot4.toPlainString(),row.points4.toPlainString(),
            row.remark,row.recordedAt,row.balanceAfter4.toPlainString())).toList();
  }
  public WalletBalance refund(String walletId,String refundId,String operationId,Decimal4 points) {
    id(walletId);id(refundId);id(operationId);positive(points);
    return db.transaction(() -> {
      lock(walletId);
      var prior=find(walletId,"REFUND",refundId);
      if(prior!=null) return replay(prior,operationId,MybatisBenefitSettlementStore.refundReplayAmount(db,walletId,refundId,points.asBigDecimal()));
      var mapper=db.mapper(BillingMappers.Allocations.class);
      var rows=mapper.selectList(new QueryWrapper<Allocation>().eq("wallet_id",walletId).eq("operation_id",operationId).eq("kind","CONSUME").orderByAsc("lot_id"));
      BigDecimal available=rows.stream().map(r->r.points4.subtract(r.refundedPoints4)).reduce(BigDecimal.ZERO,BigDecimal::add);
      if(available.compareTo(points.asBigDecimal())<0) throw new IllegalArgumentException("refund exceeds original debit");
      BigDecimal left=points.asBigDecimal();
      Instant refundedAt=Instant.now();
      BigDecimal credited=points.asBigDecimal();
      for(var row:rows) {
        if(left.signum()==0) break;
        BigDecimal restored=left.min(row.points4.subtract(row.refundedPoints4));
        if(restored.signum()==0) continue;
        // A refunded reward cannot restore revoked points while leaving recoverable debt behind.
        BigDecimal offset=MybatisBenefitSettlementStore.offsetRewardRefund(db,row.lotId,restored);
        credited=credited.subtract(offset);
        if(mapper.refund(row.id,restored)!=1 || db.mapper(BillingMappers.Lots.class).change(row.lotId,restored.subtract(offset))!=1)
          throw new IllegalStateException("refund allocation changed");
        MybatisBenefitSettlementStore.recordRefund(db,refundId,row.id,restored,refundedAt);
        left=left.subtract(restored);
      }
      MybatisBenefitSettlementStore.refundReceipt(db,walletId,refundId,points.asBigDecimal(),credited);
      return changeAndRecord(walletId,"REFUND",refundId,operationId,credited,refundedAt);
    });
  }
  /** Permanently withdraw the original recharge points before an idempotent provider refund.
   * A provider timeout keeps this reservation; retries must use the same order/refund identity. */
  public WalletBalance reserveRechargeRefund(String orderId) {
    id(orderId);
    return db.transaction(() -> {
      var recharge=db.mapper(BillingMappers.Recharges.class).selectById(orderId);
      if(recharge==null) throw new IllegalArgumentException("unknown paid recharge");
      lock(recharge.walletId);
      var prior=find(recharge.walletId,"CASH_REFUND",orderId);
      if(prior!=null) return replay(prior,orderId,recharge.points4.negate());
      var lots=db.mapper(BillingMappers.Lots.class);
      var lot=lots.selectById("recharge:"+orderId);
      if(lot==null || lot.remainingPoints4.compareTo(lot.originalPoints4)!=0)
        throw new IllegalStateException("recharge has unrefunded consumption");
      if(lots.change(lot.lotId,lot.originalPoints4.negate())!=1) throw new IllegalStateException("recharge funding changed");
      return changeAndRecord(recharge.walletId,"CASH_REFUND",orderId,orderId,recharge.points4.negate(),Instant.now());
    });
  }
  public List<Entry> entries(String walletId,boolean administrator,int limit) {
    id(walletId);if(limit<1||limit>100) throw new IllegalArgumentException("limit must be 1..100");
    var query=new QueryWrapper<Ledger>().eq("wallet_id",walletId);
    if(!administrator) query.ne("kind","ADMIN_CREDIT");
    query.orderByDesc("balance_version").last("LIMIT "+limit);
    var rows=db.mapper(BillingMappers.Ledger.class).selectList(query);
    var operations=rows.stream().filter(r->"CONSUME".equals(r.kind)).map(r->r.sourceId).distinct().toList();
    var amounts=new HashMap<String,String>();
    if(!operations.isEmpty()) {
      for(var receipt:db.mapper(MediaChargeMapper.class).selectList(new QueryWrapper<MediaChargeRow>()
          .eq("key_id",walletId).in("operation_id",operations))) {
        amounts.put(receipt.operationId,receipt.amount4.negate().toPlainString());
      }
    }
    return rows.stream().map(r->new Entry(r.requestId,r.kind,r.points4.toPlainString(),r.balanceAfter4.toPlainString(),r.recordedAt,
        "CONSUME".equals(r.kind)?amounts.get(r.sourceId):null)).toList();
  }
  Wallet lock(String walletId) {
    var row=db.mapper(BillingMappers.Wallets.class).lock(walletId);
    if(row==null) throw new IllegalArgumentException("unknown wallet");
    return row;
  }
  WalletBalance changeAndRecord(String walletId,String kind,String requestId,String source,BigDecimal delta,Instant at) {
    if(db.mapper(BillingMappers.Wallets.class).change(walletId,delta)!=1) throw new IllegalArgumentException("insufficient balance");
    var result=balance(walletId); record(kind,requestId,source,delta,at,result);return result;
  }
  void record(String kind,String requestId,String source,BigDecimal delta,Instant at,WalletBalance balance) {
    var row=new Ledger();row.entryId=UUID.randomUUID().toString();row.walletId=balance.walletId();row.requestId=requestId;
    row.kind=kind;row.sourceId=source;row.points4=delta;row.recordedAt=at.toString();
    row.balanceAfter4=balance.availablePoints4().asBigDecimal();row.balanceVersion=balance.version();
    db.mapper(BillingMappers.Ledger.class).insert(row);
  }
  private Ledger find(String wallet,String kind,String request) {
    return db.mapper(BillingMappers.Ledger.class).selectOne(new QueryWrapper<Ledger>()
        .eq("wallet_id",wallet).eq("kind",kind).eq("request_id",request));
  }
  private static WalletBalance replay(Ledger row,String source,BigDecimal points) {
    if(!row.sourceId.equals(source)||row.points4.compareTo(points)!=0) throw new IllegalStateException("idempotency facts conflict");
    return new WalletBalance(row.walletId,Decimal4.of(row.balanceAfter4),row.balanceVersion);
  }
  static void id(String id) {if(id==null||!id.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("invalid identifier");}
  static void positive(Decimal4 value) {
    if(value==null||!value.isPositive()||value.asBigDecimal().precision()>38) throw new IllegalArgumentException("invalid points");
  }
}
