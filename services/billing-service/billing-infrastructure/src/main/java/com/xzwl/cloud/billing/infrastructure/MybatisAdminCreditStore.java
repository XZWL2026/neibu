package com.xzwl.cloud.billing.infrastructure;
import com.xzwl.cloud.billing.application.credit.*;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.wallet.WalletBalance;
import com.xzwl.cloud.billing.infrastructure.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.OptimisticLockingFailureException;
public final class MybatisAdminCreditStore implements AdminCreditStore {
  private final BillingDatabase db;
  private final MybatisLedgerStore ledger;
  public MybatisAdminCreditStore(BillingDatabase db) { this.db=db;this.ledger=new MybatisLedgerStore(db); }
  public Optional<AdminCreditResult> findByRequestId(String requestId) {
    MybatisLedgerStore.id(requestId);
    var r=db.mapper(BillingMappers.AdminCredits.class).selectById(requestId);
    return r==null?Optional.empty():Optional.of(new AdminCreditResult(
        new WalletBalance(r.walletId,Decimal4.of(r.balanceAfter4),r.balanceVersion),
        new AdminCreditRecord(r.requestId,r.walletId,r.keyId,Decimal4.of(r.points4),r.adminId,Instant.parse(r.recordedAt),r.remark)));
  }
  public WalletBalance loadBalance(String walletId) {
    MybatisLedgerStore.id(walletId);
    var r=db.mapper(BillingMappers.Wallets.class).selectById(walletId);
    if(r==null) throw new IllegalArgumentException("unknown wallet");
    return MybatisLedgerStore.balance(r);
  }
  public AdminCreditResult creditAtomically(AdminCreditRecord record) {
    return db.transaction(() -> {
      var current=ledger.lock(record.walletId());
      var prior=findByRequestId(record.requestId());
      if(prior.isPresent()) {
        if(!prior.get().record().sameFacts(record)) throw new IllegalStateException("credit facts conflict");
        return prior.get();
      }
      var next=MybatisLedgerStore.balance(current).credit(record.points4());
      saveAtomically(record,next);
      return new AdminCreditResult(next,record);
    });
  }
  public void saveAtomically(AdminCreditRecord record,WalletBalance next) {
    if(!record.walletId().equals(next.walletId()))throw new IllegalArgumentException("wallet mismatch");
    MybatisLedgerStore.positive(record.points4());
    db.transaction(() -> {
      if(db.mapper(BillingMappers.Wallets.class).compareAndSet(record.walletId(),next.availablePoints4().asBigDecimal(),next.version(),
          next.availablePoints4().subtract(record.points4()).asBigDecimal(),next.version()-1)!=1)
        throw new OptimisticLockingFailureException("credit does not follow stored balance");
      var r=new BillingRows.AdminCredit();r.requestId=record.requestId();r.walletId=record.walletId();r.keyId=record.keyId();
      r.points4=record.points4().asBigDecimal();r.adminId=record.adminId();r.recordedAt=record.at().toString();r.remark=record.remark();
      r.status="SUCCEEDED";r.balanceAfter4=next.availablePoints4().asBigDecimal();r.balanceVersion=next.version();
      db.mapper(BillingMappers.AdminCredits.class).insert(r);
      var lot=new BillingRows.Lot();lot.lotId="admin:"+r.requestId;lot.walletId=r.walletId;lot.lotType="GIFT";
      lot.originalPoints4=r.points4;lot.remainingPoints4=r.points4;lot.creditedAt=r.recordedAt;lot.benefitEligible=false;
      db.mapper(BillingMappers.Lots.class).insert(lot);
      ledger.record("ADMIN_CREDIT",r.requestId,r.keyId,r.points4,record.at(),next);
      return null;
    });
  }
}
