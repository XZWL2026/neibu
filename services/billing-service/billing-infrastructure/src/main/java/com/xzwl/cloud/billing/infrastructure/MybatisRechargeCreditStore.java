package com.xzwl.cloud.billing.infrastructure;
import com.xzwl.cloud.billing.application.recharge.*;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.wallet.WalletBalance;
import com.xzwl.cloud.billing.infrastructure.persistence.*;
import java.time.Instant;
import java.util.*;
public final class MybatisRechargeCreditStore implements RechargeCreditStore {
  private final BillingDatabase db;
  public MybatisRechargeCreditStore(BillingDatabase db) {this.db=db;}
  public Optional<RechargeCreditResult> findByOrderId(String orderId) {
    MybatisLedgerStore.id(orderId);
    var r=db.mapper(BillingMappers.Recharges.class).selectById(orderId);
    return r==null?Optional.empty():Optional.of(new RechargeCreditResult(
        new WalletBalance(r.walletId,Decimal4.of(r.balanceAfter4),r.balanceVersion),
        new RechargeCreditRecord(r.orderId,r.userId,r.walletId,r.keyId,Decimal4.of(r.amount4),Decimal4.of(r.rateSnapshot4),Instant.parse(r.paidAt))));
  }
  public RechargeCreditResult creditAtomically(RechargeCreditRecord record) {
    MybatisLedgerStore.positive(record.points4());
    return db.transaction(() -> {
      var wallets=db.mapper(BillingMappers.Wallets.class);
      wallets.ensure(record.walletId());wallets.lock(record.walletId());
      var existing=findByOrderId(record.orderId());
      if(existing.isPresent()){
        if(!existing.get().record().equals(record))throw new IllegalStateException("recharge facts conflict");
        return existing.get();
      }
      var ledger=new MybatisLedgerStore(db);
      var next=ledger.changeAndRecord(record.walletId(),"RECHARGE",record.orderId(),record.orderId(),record.points4().asBigDecimal(),record.paidAt());
      var r=new BillingRows.Recharge();r.orderId=record.orderId();r.userId=record.userId();r.walletId=record.walletId();r.keyId=record.keyId();
      r.amount4=record.amount4().asBigDecimal();r.rateSnapshot4=record.rateSnapshot4().asBigDecimal();r.points4=record.points4().asBigDecimal();
      r.paidAt=record.paidAt().toString();r.balanceAfter4=next.availablePoints4().asBigDecimal();r.balanceVersion=next.version();
      db.mapper(BillingMappers.Recharges.class).insert(r);
      var lot=new BillingRows.Lot();lot.lotId="recharge:"+r.orderId;lot.walletId=r.walletId;lot.lotType="REAL_RECHARGE";
      lot.originalPoints4=r.points4;lot.remainingPoints4=r.points4;lot.creditedAt=r.paidAt;lot.benefitEligible=true;
      db.mapper(BillingMappers.Lots.class).insert(lot);
      return new RechargeCreditResult(next,record);
    });
  }
}
