package com.xzwl.cloud.promotion.infrastructure;

import com.xzwl.cloud.promotion.application.production.PromotionOperations;
import com.xzwl.cloud.promotion.application.production.PromotionStore;
import com.xzwl.cloud.promotion.application.production.PromotionStore.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses the same service database/transaction as existing promotion accounting. */
public final class MybatisPromotionOperations implements PromotionOperations {
  private static final BigDecimal ZERO=new BigDecimal("0.0000");
  private final JdbcTemplate db;
  private final PromotionStore store;
  private final TransactionTemplate tx;
  private final Clock clock;
  private java.util.function.Function<String,String> rewardDebtLookup=owner->{throw new IllegalStateException("reward debt service unavailable");};
  public void setRewardDebtLookup(java.util.function.Function<String,String> lookup) {rewardDebtLookup=Objects.requireNonNull(lookup);}
  private String currentDebt(String owner) {return amount(decimal(rewardDebtLookup.apply(owner),false));}
  public MybatisPromotionOperations(JdbcTemplate db,PromotionStore store,PlatformTransactionManager manager,Clock clock) {
    this.db=db;this.store=store;this.clock=clock;tx=new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }
  private <T>T transaction(Supplier<T> action) {return tx.execute(s->action.get());}
  private Map<String,Object> one(String sql,Object... args) {
    var rows=db.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();
  }
  private static String text(Map<String,Object> r,String k) {var v=r.get(k);return v==null?null:v.toString();}
  private static BigDecimal number(Map<String,Object> r,String k) {var v=r.get(k);return v==null?ZERO:new BigDecimal(v.toString()).setScale(4);}
  private static long integer(Map<String,Object> r,String k) {return ((Number)r.get(k)).longValue();}
  private static boolean flag(Map<String,Object> r,String k) {var v=r.get(k);return Boolean.TRUE.equals(v)||(v instanceof Number n&&n.intValue()!=0);}
  private static String amount(BigDecimal n) {return n.setScale(4,RoundingMode.DOWN).toPlainString();}
  private static BigDecimal decimal(String value,boolean positive) {
    if(value==null||!value.matches("(?:0|[1-9][0-9]{0,33})(?:\\.[0-9]{1,4})?"))throw new IllegalArgumentException("invalid amount");
    var n=new BigDecimal(value).setScale(4);if(positive&&n.signum()<=0)throw new IllegalArgumentException("positive amount required");return n;
  }
  private static void id(String id) {if(id==null||!id.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("invalid identifier");}
  private static void reason(String value) {if(value==null||value.isBlank()||value.length()>2000)throw new IllegalArgumentException("reason required");}
  private static void page(int page,int size) {if(page<0||page>1000000||size<1||size>100)throw new IllegalArgumentException("invalid page");}
  private static String key(String prefix,String value) {return prefix+UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));}
  private static String facts(Object... values) {
    StringBuilder b=new StringBuilder();for(Object value:values){String v=Objects.toString(value,"");b.append(v.length()).append(':').append(v);}return b.toString();
  }
  private static String time(Instant at) {return java.time.format.DateTimeFormatter.ISO_INSTANT.format(Objects.requireNonNull(at));}
  @Override public void recordRecharge(PaidRecharge c) {
    id(c.orderId());id(c.userId());String lot="recharge:"+c.orderId();
    transaction(()->{
      var row=one("SELECT * FROM promotion_reward_funding WHERE funding_lot_id=? FOR UPDATE",lot);
      if(row==null) {
        // Both cash-only and points-only relationships are frozen at order creation.
        var binding=one("SELECT * FROM referral_binding WHERE referred_user_id=? AND effective_at<=? ORDER BY effective_at DESC,version DESC LIMIT 1",c.userId(),timestamp(c.createdAt()));
        var policy=one("SELECT * FROM promotion_benefit_policy WHERE effective_at<=? ORDER BY effective_at DESC LIMIT 1",timestamp(c.createdAt()));
        if(policy==null)throw new IllegalStateException("historical policy unavailable");
        Object beneficiary=binding==null?null:binding.get("point_beneficiary_user_id");
        var override=beneficiary==null?null:one("SELECT point_rate6 FROM promotion_customer_point_override WHERE policy_version=? AND customer_user_id=?",policy.get("version"),beneficiary);
        db.update("INSERT INTO promotion_reward_funding(funding_lot_id,order_id,owner_id,paid_amount4,beneficiary_id,point_rate6,binding_version,policy_version,created_at,paid_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            lot,c.orderId(),c.userId(),c.amount4(),beneficiary,override==null?policy.get("point_rate6"):override.get("point_rate6"),binding==null?0:binding.get("version"),policy.get("version"),time(c.createdAt()),time(c.paidAt()));
        row=one("SELECT * FROM promotion_reward_funding WHERE funding_lot_id=? FOR UPDATE",lot);
      }
      if(!c.userId().equals(text(row,"owner_id"))||c.amount4().compareTo(number(row,"paid_amount4"))!=0
          ||!c.createdAt().equals(Instant.parse(text(row,"created_at")))||!c.paidAt().equals(Instant.parse(text(row,"paid_at"))))throw new IllegalStateException("recharge snapshot conflict");
      if(c.refundedAt()!=null&&!flag(row,"refunded")) {
        db.update("UPDATE promotion_reward_funding SET refunded=TRUE WHERE funding_lot_id=?",lot);
        for(var allocation:db.queryForList("SELECT * FROM promotion_success_allocation WHERE funding_lot_id=? FOR UPDATE",lot)) {
          var reward=number(allocation,"reward_points4");
          if(reward.signum()>0&&text(row,"beneficiary_id")!=null) {
            enqueueReward(key("refund-reward:",c.orderId()+":"+text(allocation,"allocation_id")),"REVERSE",text(row,"beneficiary_id"),allocation,reward,c.refundedAt());
            db.update("UPDATE promotion_success_allocation SET reward_points4=0 WHERE allocation_id=?",allocation.get("allocation_id"));
          }
        }
      }
      return null;
    });
  }
  private static String timestamp(Instant at) {return new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter().format(at);}
  @Override public void settle(ConsumptionSettlement c) {
    for(String value:new String[]{c.eventId(),c.settlementId(),c.allocationId(),c.fundingLotId(),c.orderId(),c.walletId(),c.walletOwnerUserId()})id(value);
    if(!Set.of("SETTLED","REVERSED").contains(c.kind()))throw new IllegalArgumentException("invalid settlement kind");
    BigDecimal originalPaid=decimal(c.originalPaidAmount4(),true),originalPoints=decimal(c.originalPoints4(),true),successful=decimal(c.successfulPoints4(),true),reversed=decimal(c.reversedPoints4(),false);
    if(successful.compareTo(originalPoints)>0||reversed.compareTo(successful)>0||("SETTLED".equals(c.kind())?reversed.signum()!=0:reversed.signum()==0))throw new IllegalArgumentException("invalid settlement points");
    String semantic=facts(c.eventId(),c.kind(),c.settlementId(),c.allocationId(),c.fundingLotId(),c.orderId(),c.walletId(),c.walletOwnerUserId(),originalPaid,originalPoints,successful,reversed,time(c.occurredAt()));
    transaction(()->{
      var snapshot=one("SELECT * FROM promotion_reward_funding WHERE funding_lot_id=? FOR UPDATE",c.fundingLotId());
      if(snapshot==null)throw new IllegalStateException("paid recharge dependency pending");
      var receipt=one("SELECT * FROM promotion_consumption_receipt WHERE event_id=?",c.eventId());
      if(receipt!=null){if(!semantic.equals(text(receipt,"facts")))throw new IllegalStateException("settlement facts conflict");return null;}
      if(!c.orderId().equals(text(snapshot,"order_id"))||!c.walletOwnerUserId().equals(text(snapshot,"owner_id"))||originalPaid.compareTo(number(snapshot,"paid_amount4"))!=0
          ||snapshot.get("original_points4")!=null&&originalPoints.compareTo(number(snapshot,"original_points4"))!=0)throw new IllegalStateException("funding facts conflict");
      var allocation=one("SELECT * FROM promotion_success_allocation WHERE allocation_id=? FOR UPDATE",c.allocationId());
      BigDecimal oldNet=number(snapshot,"net_successful_points4"),newNet;
      BigDecimal rewardChange;
      String beneficiary=text(snapshot,"beneficiary_id");
      if("SETTLED".equals(c.kind())) {
        if(allocation!=null||db.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger WHERE allocation_id=?",Long.class,c.allocationId())>0)throw new IllegalStateException("allocation already settled");
        newNet=oldNet.add(successful);
        rewardChange=flag(snapshot,"refunded")||beneficiary==null?ZERO:successful.multiply(new BigDecimal(text(snapshot,"point_rate6"))).setScale(4,RoundingMode.DOWN);
        db.update("INSERT INTO promotion_success_allocation(allocation_id,settlement_id,funding_lot_id,wallet_id,successful_points4,reward_points4,occurred_at) VALUES(?,?,?,?,?,?,?)",
            c.allocationId(),c.settlementId(),c.fundingLotId(),c.walletId(),successful,rewardChange,time(c.occurredAt()));
        allocation=one("SELECT * FROM promotion_success_allocation WHERE allocation_id=?",c.allocationId());
      } else {
        if(allocation==null)throw new IllegalStateException("settlement dependency pending");
        if(!c.settlementId().equals(text(allocation,"settlement_id"))||!c.fundingLotId().equals(text(allocation,"funding_lot_id"))||!c.walletId().equals(text(allocation,"wallet_id"))||successful.compareTo(number(allocation,"successful_points4"))!=0)throw new IllegalStateException("original allocation conflict");
        BigDecimal totalReversed=number(allocation,"reversed_points4").add(reversed);
        if(totalReversed.compareTo(successful)>0)throw new IllegalStateException("reversal exceeds success");
        newNet=oldNet.subtract(reversed);
        BigDecimal remainingReward=flag(snapshot,"refunded")||beneficiary==null?ZERO:successful.subtract(totalReversed).multiply(new BigDecimal(text(snapshot,"point_rate6"))).setScale(4,RoundingMode.DOWN);
        rewardChange=number(allocation,"reward_points4").subtract(remainingReward);
        db.update("UPDATE promotion_success_allocation SET reversed_points4=?,reward_points4=? WHERE allocation_id=?",totalReversed,remainingReward,c.allocationId());
      }
      if(newNet.signum()<0||newNet.compareTo(originalPoints)>0)throw new IllegalStateException("successful funding exhausted");
      cash(c,snapshot,oldNet,newNet,originalPoints);
      if(beneficiary!=null&&rewardChange.signum()>0)enqueueReward(key("point:",c.eventId()),"SETTLED".equals(c.kind())?"GRANT":"REVERSE",beneficiary,allocation,rewardChange,c.occurredAt());
      db.update("UPDATE promotion_reward_funding SET original_points4=?,net_successful_points4=? WHERE funding_lot_id=?",originalPoints,newNet,c.fundingLotId());
      db.update("INSERT INTO promotion_consumption_receipt(event_id,facts,recorded_at) VALUES(?,?,?)",c.eventId(),semantic,time(c.occurredAt()));
      return null;
    });
  }
  private void cash(ConsumptionSettlement c,Map<String,Object> snapshot,BigDecimal oldNet,BigDecimal newNet,BigDecimal originalPoints) {
    var funding=one("SELECT * FROM promotion_funding_benefit WHERE funding_lot_id=? FOR UPDATE",c.fundingLotId());
    if(funding==null)return;
    if(db.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger l WHERE l.funding_lot_id=? AND l.action_type='COMMISSION_RELEASED' AND NOT EXISTS(SELECT 1 FROM promotion_consumption_receipt r WHERE r.event_id=l.source_event_id)",Long.class,c.fundingLotId())>0)
      throw new IllegalStateException("legacy funding settlement requires reconciliation before migration");
    // A full recharge refund may overtake both settlement and reversal delivery; it already owns the clawback.
    if(number(funding,"refunded_paid_amount4").compareTo(number(funding,"paid_amount4"))==0)return;
    BigDecimal frozen=number(funding,"frozen_commission4");
    BigDecimal alreadyClawed=number(funding,"reversed_released_commission4");
    BigDecimal before=number(funding,"released_commission4").subtract(alreadyClawed);
    BigDecimal remainingEntitlement=frozen.subtract(number(funding,"clawed_unreleased_commission4")).subtract(alreadyClawed);
    BigDecimal after=frozen.multiply(newNet).divide(originalPoints,4,RoundingMode.DOWN).min(remainingEntitlement);
    BigDecimal delta=after.subtract(before),paid=number(funding,"paid_amount4");
    BigDecimal settledPaid=paid.multiply(newNet).divide(originalPoints,4,RoundingMode.DOWN);
    var account=one("SELECT * FROM promotion_commission_account WHERE promoter_user_id=? FOR UPDATE",funding.get("promoter_user_id"));
    BigDecimal available=number(account,"available4"),debt=number(account,"cash_debt4"),accountFrozen=number(account,"frozen4");
    if(delta.signum()>=0) {BigDecimal offset=delta.min(debt);available=available.add(delta.subtract(offset));debt=debt.subtract(offset);accountFrozen=accountFrozen.subtract(delta);}
    else {BigDecimal restore=delta.negate(),recover=restore.min(available);available=available.subtract(recover);debt=debt.add(restore.subtract(recover));accountFrozen=accountFrozen.add(restore);}
    if(accountFrozen.signum()<0)throw new IllegalStateException("cash funding mismatch");
    long version=integer(account,"version")+1;
    db.update("UPDATE promotion_commission_account SET frozen4=?,available4=?,cash_debt4=?,version=? WHERE promoter_user_id=?",accountFrozen,available,debt,version,account.get("promoter_user_id"));
    db.update("UPDATE promotion_funding_benefit SET released_commission4=released_commission4+?,settled_paid_amount4=? WHERE funding_lot_id=?",delta,settledPaid,c.fundingLotId());
    String action=delta.signum()<0?"CONSUMPTION_RELEASE_REVERSED":"COMMISSION_RELEASED";
    BigDecimal paidChange=paid.multiply(newNet).divide(originalPoints,4,RoundingMode.DOWN).subtract(paid.multiply(oldNet).divide(originalPoints,4,RoundingMode.DOWN));
    db.update("INSERT INTO promotion_commission_ledger(promoter_user_id,sequence_no,action_type,amount4,source_event_id,order_id,funding_lot_id,allocation_id,created_at,successful_paid_amount4) VALUES(?,?,?,?,?,?,?,?,?,?)",
        account.get("promoter_user_id"),version,action,delta.abs(),c.eventId(),c.orderId(),c.fundingLotId(),c.allocationId(),timestamp(c.occurredAt()),paidChange);
  }
  private void enqueueReward(String event,String kind,String beneficiary,Map<String,Object> allocation,BigDecimal points,Instant at) {
    db.update("INSERT INTO promotion_reward_delivery(event_id,kind,entitlement_id,beneficiary_id,settlement_id,allocation_id,points4,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
        event,kind,key("reward:",text(allocation,"allocation_id")),beneficiary,allocation.get("settlement_id"),allocation.get("allocation_id"),points,time(at));
  }
  @Override public List<RewardDelivery> pendingRewards(int limit) {
    if(limit<1||limit>100)throw new IllegalArgumentException("invalid limit");
    return db.queryForList("SELECT d.* FROM promotion_reward_delivery d WHERE d.delivered_at IS NULL AND NOT EXISTS(SELECT 1 FROM promotion_reward_delivery p WHERE p.beneficiary_id=d.beneficiary_id AND p.sequence_no<d.sequence_no AND p.delivered_at IS NULL) ORDER BY d.sequence_no LIMIT ?",limit).stream()
        .map(r->new RewardDelivery(text(r,"event_id"),text(r,"kind"),text(r,"entitlement_id"),text(r,"beneficiary_id"),text(r,"settlement_id"),text(r,"allocation_id"),amount(number(r,"points4")),Instant.parse(text(r,"occurred_at")))).toList();
  }
  @Override public void rewardDelivered(String event,RewardReceipt receipt) {
    id(event);id(receipt.walletId());decimal(receipt.creditedPoints4(),false);decimal(receipt.recoveredPoints4(),false);decimal(receipt.debtPoints4(),false);
    transaction(()->{
      var row=one("SELECT * FROM promotion_reward_delivery WHERE event_id=? FOR UPDATE",event);if(row==null)throw new IllegalArgumentException("unknown reward delivery");
      if(row.get("delivered_at")!=null) {
        if(!receipt.walletId().equals(text(row,"wallet_id"))||new BigDecimal(receipt.creditedPoints4()).compareTo(number(row,"credited_points4"))!=0||new BigDecimal(receipt.recoveredPoints4()).compareTo(number(row,"recovered_points4"))!=0||new BigDecimal(receipt.debtPoints4()).compareTo(number(row,"debt_points4"))!=0)throw new IllegalStateException("reward receipt conflict");
        return null;
      }
      db.update("UPDATE promotion_reward_delivery SET delivered_at=?,wallet_id=?,credited_points4=?,recovered_points4=?,debt_points4=? WHERE event_id=?",time(clock.instant()),receipt.walletId(),receipt.creditedPoints4(),receipt.recoveredPoints4(),receipt.debtPoints4(),event);
      return null;
    });
  }
  @Override public RewardAccount rewards(String owner,int page,int size) {
    id(owner);page(page,size);
    var sums=one("SELECT COALESCE(SUM(CASE WHEN kind='GRANT' THEN points4 ELSE -points4 END),0) earned,COALESCE(SUM(credited_points4),0) posted,COALESCE(SUM(CASE WHEN kind='REVERSE' THEN points4 ELSE 0 END),0) reversed,COUNT(*) total FROM promotion_reward_delivery WHERE beneficiary_id=?",owner);
    String debt=currentDebt(owner);
    var items=db.queryForList("SELECT d.*,f.order_id FROM promotion_reward_delivery d JOIN promotion_success_allocation a ON a.allocation_id=d.allocation_id JOIN promotion_reward_funding f ON f.funding_lot_id=a.funding_lot_id WHERE d.beneficiary_id=? ORDER BY d.sequence_no DESC LIMIT ? OFFSET ?",owner,size,(long)page*size).stream()
        .map(r->new RewardEntry(text(r,"event_id"),text(r,"kind"),amount(number(r,"points4")),r.get("delivered_at")==null?"PENDING":"POSTED",text(r,"settlement_id"),text(r,"allocation_id"),text(r,"order_id"),r.get("credited_points4")==null?null:amount(number(r,"credited_points4")),r.get("recovered_points4")==null?null:amount(number(r,"recovered_points4")),r.get("debt_points4")==null?null:amount(number(r,"debt_points4")),Instant.parse(text(r,"occurred_at")))).toList();
    return new RewardAccount(amount(number(sums,"earned")),amount(number(sums,"posted")),amount(number(sums,"reversed")),debt,items,page,size,integer(sums,"total"));
  }
  private Map<String,Object> adminReceipt(String command,String kind,String subject,String actor,String semantics) {
    id(command);id(subject);id(actor);
    // ponytail: low-volume administrator commands share the permanent policy lock; split by subject if contention appears.
    db.queryForList("SELECT version FROM promotion_benefit_policy WHERE version=1 FOR UPDATE");
    var saved=one("SELECT * FROM promotion_admin_operation WHERE command_id=?",command);
    if(saved!=null&&(!kind.equals(text(saved,"kind"))||!subject.equals(text(saved,"subject_id"))||!actor.equals(text(saved,"admin_id"))||!semantics.equals(text(saved,"facts"))))throw new IllegalStateException("administrator command facts conflict");
    return saved;
  }
  private void adminRecord(String command,String kind,String subject,String actor,String semantics,String reason,Instant at) {
    db.update("INSERT INTO promotion_admin_operation(command_id,kind,subject_id,admin_id,facts,reason,occurred_at) VALUES(?,?,?,?,?,?,?)",command,kind,subject,actor,semantics,reason,time(at));
  }
  @Override public ReferralAdjustmentPage referralAdjustments(String userId,int page,int size) {
    page(page,size);if(userId!=null)id(userId);
    String where=" WHERE a.kind='REFERRAL'"+(userId==null?"":" AND a.subject_id=?");
    Object[] filter=userId==null?new Object[]{}:new Object[]{userId};
    long total=db.queryForObject("SELECT COUNT(*) FROM promotion_admin_operation a JOIN referral_binding b ON b.command_id=a.command_id"+where,Long.class,filter);
    var args=new ArrayList<Object>(Arrays.asList(filter));args.add(size);args.add((long)page*size);
    var rows=db.queryForList("SELECT a.command_id,a.subject_id,a.admin_id,a.reason,a.occurred_at,b.direct_inviter_user_id,p.direct_inviter_user_id AS previous_inviter_id FROM promotion_admin_operation a JOIN referral_binding b ON b.command_id=a.command_id LEFT JOIN referral_binding p ON p.referred_user_id=b.referred_user_id AND p.version=b.version-1"+where+" ORDER BY b.effective_at DESC,a.command_id DESC LIMIT ? OFFSET ?",args.toArray());
    return new ReferralAdjustmentPage(rows.stream().map(row->new ReferralAdjustment(text(row,"command_id"),text(row,"subject_id"),text(row,"previous_inviter_id"),text(row,"direct_inviter_user_id"),text(row,"admin_id"),text(row,"reason"),Instant.parse(text(row,"occurred_at")))).toList(),page,size,total);
  }
  @Override public Attribution attribution(String userId) {
    id(userId);
    var rows=store.listReferralHistory(userId,0,100).items();
    var history=rows.stream().map(binding->{
      var original=one("SELECT command_id FROM referral_binding WHERE referred_user_id=? AND version=?",userId,binding.version());
      var audit=original==null?null:one("SELECT * FROM promotion_admin_operation WHERE command_id=? AND kind='REFERRAL'",original.get("command_id"));
      return new ReferralHistoryEntry(binding,audit==null?null:text(audit,"admin_id"),audit==null?null:text(audit,"reason"));
    }).toList();
    return new Attribution(rows.isEmpty()?null:rows.getFirst(),history);
  }
  @Override public Attribution rebind(Rebind c) {
    id(c.commandId());id(c.userId());id(c.inviterUserId());id(c.adminId());reason(c.reason());if(c.expectedVersion()<0)throw new IllegalArgumentException("invalid version");
    return transaction(()->{
      String semantic=facts(c.userId(),c.inviterUserId(),c.expectedVersion(),c.reason());
      if(adminReceipt(c.commandId(),"REFERRAL",c.userId(),c.adminId(),semantic)!=null)return attribution(c.userId());
      var implementation=(MyBatisPromotionStore)store;
      var user=implementation.applicantForOperations(c.userId());var inviter=implementation.applicantForOperations(c.inviterUserId());
      boolean promoter=one("SELECT promoter_user_id FROM promoter_account WHERE promoter_user_id=? AND status='ENABLED'",c.inviterUserId())!=null;
      if(!user.enabled()||!inviter.enabled()||!promoter&&!Set.of("PERSONAL","API","ENTERPRISE").contains(inviter.accountType()))throw new IllegalArgumentException("user or inviter is ineligible");
      Instant now=clock.instant();
      store.bindReferral(new BindReferral(c.commandId(),c.userId(),c.inviterUserId(),c.expectedVersion(),now));
      adminRecord(c.commandId(),"REFERRAL",c.userId(),c.adminId(),semantic,c.reason(),now);
      return attribution(c.userId());
    });
  }
  @Override public Payout createPayout(CreatePayout c) {
    id(c.commandId());id(c.withdrawalId());id(c.promoterUserId());id(c.payoutAccountId());id(c.adminId());
    if(c.settlementMonth()==null||!c.settlementMonth().matches("[0-9]{4}-(?:0[1-9]|1[0-2])"))throw new IllegalArgumentException("invalid settlement month");
    YearMonth month=YearMonth.parse(c.settlementMonth());if(month.isAfter(YearMonth.now(clock.withZone(ZoneId.of("Asia/Shanghai")))))throw new IllegalArgumentException("future settlement month");
    BigDecimal amount=decimal(c.amount4(),true);if(amount.stripTrailingZeros().scale()>2)throw new IllegalArgumentException("whole cents required");
    if(c.expectedDraftVersion()!=null&&c.expectedDraftVersion()<0)throw new IllegalArgumentException("invalid draft version");
    String semantic=c.expectedDraftVersion()==null?facts(c.withdrawalId(),c.promoterUserId(),c.settlementMonth(),amount,c.payoutAccountId())
        :facts(c.withdrawalId(),c.promoterUserId(),c.settlementMonth(),amount,c.payoutAccountId(),c.expectedDraftVersion());
    return transaction(()->{
      if(adminReceipt(c.commandId(),"PAYOUT_CREATE",c.withdrawalId(),c.adminId(),semantic)!=null)return payout(c.withdrawalId());
      var draft=lockIncomeDraft(c.promoterUserId(),c.settlementMonth());
      var monthRecord=one("SELECT * FROM promotion_payout_month WHERE promoter_user_id=? AND settlement_month=?",c.promoterUserId(),c.settlementMonth());
      Instant now=clock.instant();
      if(monthRecord==null) {
        long draftVersion=integer(draft,"adjustment_version");
        if((draftVersion>0&&(c.expectedDraftVersion()==null||!c.withdrawalId().equals(text(draft,"withdrawal_id"))))
            ||(c.expectedDraftVersion()!=null&&c.expectedDraftVersion()!=draftVersion))throw new IllegalStateException("income draft changed");
        if(amount.add(number(draft,"adjustment_amount4")).signum()<=0)throw new IllegalArgumentException("adjusted amount must be positive");
        db.update("INSERT INTO promotion_payout_month(withdrawal_id,promoter_user_id,settlement_month) VALUES(?,?,?)",c.withdrawalId(),c.promoterUserId(),c.settlementMonth());
        store.requestWithdrawal(new RequestWithdrawal(c.commandId(),c.withdrawalId(),c.promoterUserId(),amount,c.payoutAccountId(),now));
        db.update("UPDATE promotion_payout_month SET base_amount4=?,adjustment_amount4=?,adjustment_version=? WHERE withdrawal_id=?",amount,number(draft,"adjustment_amount4"),draftVersion,c.withdrawalId());
      } else {
        if(!c.withdrawalId().equals(text(monthRecord,"withdrawal_id")))throw new IllegalStateException("monthly payout already exists; reopen the rejected record");
        reopen(c,amount,now);
        db.update("UPDATE promotion_payout_month SET base_amount4=?,adjustment_amount4=0,adjustment_version=CASE WHEN adjustment_version=0 THEN 0 ELSE adjustment_version+1 END WHERE withdrawal_id=?",amount,c.withdrawalId());
      }
      db.update("INSERT INTO promotion_payout_attempt(command_id,withdrawal_id,amount4,payout_account_id,source_version,account_name,account_reference,bank_name,qr_code_reference,recorded_at) SELECT ?,withdrawal_id,amount4,payout_account_id,payout_account_source_version,payout_account_name,payout_account_reference,payout_bank_name,payout_qr_code_reference,? FROM promotion_withdrawal WHERE withdrawal_id=?",c.commandId(),time(now),c.withdrawalId());
      adminRecord(c.commandId(),"PAYOUT_CREATE",c.withdrawalId(),c.adminId(),semantic,null,now);return payout(c.withdrawalId());
    });
  }
  private void reopen(CreatePayout c,BigDecimal amount,Instant at) {
    var previous=one("SELECT * FROM promotion_withdrawal WHERE withdrawal_id=? FOR UPDATE",c.withdrawalId());
    if(previous==null||!"REJECTED".equals(text(previous,"status"))||!c.promoterUserId().equals(text(previous,"promoter_user_id")))throw new IllegalStateException("only a rejected payout can be resubmitted");
    var promoter=one("SELECT * FROM promoter_account WHERE promoter_user_id=? FOR UPDATE",c.promoterUserId());
    if(promoter==null||!"ENABLED".equals(text(promoter,"status")))throw new IllegalStateException("promoter is disabled");
    var payout=one("SELECT * FROM promotion_payout_account WHERE payout_account_id=? FOR UPDATE",c.payoutAccountId());
    if(payout==null||!c.promoterUserId().equals(text(payout,"owner_user_id"))||!"ACTIVE".equals(text(payout,"status"))||Instant.parse(text(payout,"updated_at")).isAfter(at))throw new IllegalStateException("payout account is unavailable");
    var account=one("SELECT * FROM promotion_commission_account WHERE promoter_user_id=? FOR UPDATE",c.promoterUserId());
    if(account==null||number(account,"cash_debt4").signum()>0||number(account,"available4").compareTo(amount)<0)throw new IllegalStateException("available commission is insufficient or has debt");
    long version=integer(account,"version")+1;
    db.update("UPDATE promotion_commission_account SET available4=available4-?,withdrawing4=withdrawing4+?,version=? WHERE promoter_user_id=?",amount,amount,version,c.promoterUserId());
    db.update("UPDATE promotion_withdrawal SET amount4=?,status='WITHDRAWING',payout_account_id=?,payout_account_source_version=?,payout_account_snapshot_at=?,payout_type=?,payout_account_name=?,payout_account_reference=?,payout_bank_name=?,payout_qr_code_reference=?,rejection_reason=NULL,updated_at=? WHERE withdrawal_id=?",
        amount,c.payoutAccountId(),payout.get("source_version"),payout.get("updated_at"),payout.get("payout_type"),payout.get("payout_account_name"),payout.get("payout_account_reference"),payout.get("bank_name"),payout.get("qr_code_reference"),timestamp(at),c.withdrawalId());
    db.update("INSERT INTO promotion_commission_ledger(promoter_user_id,sequence_no,action_type,amount4,source_event_id,withdrawal_id,created_at) VALUES(?,?,'WITHDRAWAL_REQUESTED',?,?,?,?)",c.promoterUserId(),version,amount,c.commandId(),c.withdrawalId(),timestamp(at));
    db.update("INSERT INTO promotion_withdrawal_transition(command_id,withdrawal_id,action_type,operator_id,changed_at,result_status) VALUES(?,?,'RESUBMIT',?,?,'WITHDRAWING')",c.commandId(),c.withdrawalId(),c.adminId(),timestamp(at));
  }
  @Override public Payout decidePayout(DecidePayout c) {
    id(c.commandId());id(c.withdrawalId());id(c.adminId());
    if(!c.approved())reason(c.reason());
    String semantic=c.expectedAdjustmentVersion()==null&&c.expectedAmount4()==null
        ?facts(c.withdrawalId(),c.approved(),c.reason())
        :facts(c.withdrawalId(),c.approved(),c.reason(),c.expectedAdjustmentVersion(),c.expectedAmount4());
    return transaction(()->{
      if(adminReceipt(c.commandId(),"PAYOUT_DECIDE",c.withdrawalId(),c.adminId(),semantic)!=null)return payout(c.withdrawalId());
      var withdrawal=one("SELECT * FROM promotion_withdrawal WHERE withdrawal_id=? FOR UPDATE",c.withdrawalId());
      if(withdrawal==null)throw new IllegalArgumentException("payout not found");
      var month=one("SELECT * FROM promotion_payout_month WHERE withdrawal_id=? FOR UPDATE",c.withdrawalId());
      BigDecimal delta=month==null?ZERO:number(month,"adjustment_amount4");
      BigDecimal finalAmount=number(withdrawal,"amount4");
      boolean awaiting="WITHDRAWING".equals(text(withdrawal,"status"));
      if(awaiting)finalAmount=finalAmount.add(delta);
      long version=month==null?0:integer(month,"adjustment_version");
      if(version>0&&(c.expectedAdjustmentVersion()==null||c.expectedAdjustmentVersion()!=version||c.expectedAmount4()==null||decimal(c.expectedAmount4(),true).compareTo(finalAmount)!=0))
        throw new IllegalStateException("income review snapshot changed");
      Instant now=clock.instant();
      if(c.approved()&&awaiting&&delta.signum()!=0) {
        var account=one("SELECT * FROM promotion_commission_account WHERE promoter_user_id=? FOR UPDATE",withdrawal.get("promoter_user_id"));
        BigDecimal reserved=number(account,"withdrawing4");
        if(finalAmount.signum()<=0||reserved.compareTo(number(withdrawal,"amount4"))<0||reserved.add(delta).signum()<0)throw new IllegalStateException("invalid adjusted reservation");
        long sequence=integer(account,"version")+1;
        db.update("UPDATE promotion_commission_account SET withdrawing4=withdrawing4+?,version=? WHERE promoter_user_id=?",delta,sequence,withdrawal.get("promoter_user_id"));
        db.update("UPDATE promotion_withdrawal SET amount4=? WHERE withdrawal_id=?",finalAmount,c.withdrawalId());
        db.update("INSERT INTO promotion_commission_ledger(promoter_user_id,sequence_no,action_type,amount4,source_event_id,withdrawal_id,created_at) VALUES(?,?,?,?,?,?,?)",
            withdrawal.get("promoter_user_id"),sequence,delta.signum()>0?"INCOME_ADJUSTMENT_INCREASE":"INCOME_ADJUSTMENT_DECREASE",delta.abs(),key("income:",c.commandId()),c.withdrawalId(),timestamp(now));
      }
      store.decideWithdrawal(new DecideWithdrawal(c.commandId(),c.withdrawalId(),c.adminId(),c.approved(),c.reason(),now));
      db.update("UPDATE promotion_income_adjustment SET status=? WHERE withdrawal_id=? AND status='PENDING'",c.approved()?"APPROVED":"REJECTED",c.withdrawalId());
      adminRecord(c.commandId(),"PAYOUT_DECIDE",c.withdrawalId(),c.adminId(),semantic,c.reason(),now);return payout(c.withdrawalId());
    });
  }
  @Override public Payout completePayout(CompletePayout c) {
    if(c.transferReference()==null||c.transferReference().isBlank()||c.transferReference().length()>256||c.transferReference().chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("transfer reference required");
    if(c.paidAt()==null||c.paidAt().isAfter(clock.instant()))throw new IllegalArgumentException("invalid payment time");
    String semantic=facts(c.withdrawalId(),c.transferReference(),c.paidAt());
    return transaction(()->{
      if(adminReceipt(c.commandId(),"PAYOUT_COMPLETE",c.withdrawalId(),c.adminId(),semantic)!=null)return payout(c.withdrawalId());
      var existing=payout(c.withdrawalId());if(c.paidAt().isBefore(existing.requestedAt()))throw new IllegalArgumentException("payment precedes payout");
      // Legacy pending withdrawals can also receive proof; their month remains unspecified.
      var month=one("SELECT * FROM promotion_payout_month WHERE withdrawal_id=?",c.withdrawalId());
      if(month==null)db.update("INSERT INTO promotion_payout_month(withdrawal_id,promoter_user_id,settlement_month) VALUES(?,?,NULL)",c.withdrawalId(),existing.promoterUserId());
      Instant now=clock.instant();store.completeWithdrawal(new CompleteWithdrawal(c.commandId(),c.withdrawalId(),c.adminId(),now));
      db.update("UPDATE promotion_payout_month SET transfer_reference=?,paid_at=? WHERE withdrawal_id=?",c.transferReference(),time(c.paidAt()),c.withdrawalId());
      adminRecord(c.commandId(),"PAYOUT_COMPLETE",c.withdrawalId(),c.adminId(),semantic,null,now);return payout(c.withdrawalId());
    });
  }
  private static final String PAYOUT_SELECT="SELECT w.*,m.settlement_month,m.transfer_reference,m.paid_at,m.base_amount4,m.adjustment_amount4,m.adjustment_version FROM promotion_withdrawal w LEFT JOIN promotion_payout_month m ON m.withdrawal_id=w.withdrawal_id";
  private Payout payout(String id) {var row=one(PAYOUT_SELECT+" WHERE w.withdrawal_id=?",id);if(row==null)throw new IllegalArgumentException("payout not found");return mapPayout(row);}
  private static String mask(String value) {if(value==null||value.isEmpty())return "";return value.length()<5?value.substring(0,1)+"***":"***"+value.substring(value.length()-4);}
  private static Payout mapPayout(Map<String,Object> r) {
    return new Payout(text(r,"withdrawal_id"),text(r,"promoter_user_id"),text(r,"settlement_month"),amount(number(r,"amount4")),text(r,"status"),text(r,"payout_account_id"),text(r,"payout_type"),mask(text(r,"payout_account_name")),mask(text(r,"payout_account_reference")),text(r,"rejection_reason"),text(r,"transfer_reference"),r.get("paid_at")==null?null:Instant.parse(text(r,"paid_at")),Instant.parse(text(r,"requested_at")),Instant.parse(text(r,"updated_at")),
        amount(r.get("base_amount4")==null?number(r,"amount4"):number(r,"base_amount4")),amount(number(r,"adjustment_amount4")),r.get("adjustment_version")==null?0:integer(r,"adjustment_version"),
        amount(number(r,"amount4").add("WITHDRAWING".equals(text(r,"status"))?number(r,"adjustment_amount4"):ZERO)));
  }
  @Override public PayoutDetails payoutDetails(String id,String adminId) {
    id(id);id(adminId);return transaction(()->{
      var r=one("SELECT * FROM promotion_withdrawal WHERE withdrawal_id=?",id);if(r==null)throw new IllegalArgumentException("payout not found");
      adminRecord(UUID.randomUUID().toString(),"PAYOUT_ACCOUNT_READ",id,adminId,"",null,clock.instant());
      return new PayoutDetails(id,text(r,"payout_type"),text(r,"payout_account_name"),text(r,"payout_account_reference"),text(r,"payout_bank_name"),text(r,"payout_qr_code_reference"),Instant.parse(text(r,"payout_account_snapshot_at")));
    });
  }
  @Override public Page<PortalPayoutAccount> payoutAccounts(String owner,int page,int size) {id(owner);page(page,size);return store.portalPayoutAccounts(owner,page,size);}
  @Override public PayoutPage payouts(String status,String owner,String month,int page,int size) {
    page(page,size);var args=new ArrayList<Object>();StringBuilder where=new StringBuilder(" WHERE 1=1");
    if(status!=null&&!status.isBlank()&&!"ALL".equals(status)){WithdrawalStatus.valueOf(status);where.append(" AND w.status=?");args.add(status);}
    if(owner!=null&&!owner.isBlank()){id(owner);where.append(" AND w.promoter_user_id=?");args.add(owner);}
    if("UNASSIGNED".equals(month))where.append(" AND m.settlement_month IS NULL");
    else if(month!=null&&!month.isBlank()){YearMonth.parse(month);where.append(" AND m.settlement_month=?");args.add(month);}
    String tables=" FROM promotion_withdrawal w LEFT JOIN promotion_payout_month m ON m.withdrawal_id=w.withdrawal_id";
    var total=one("SELECT COUNT(*) total,COALESCE(SUM(w.amount4),0) amount4"+tables+where,args.toArray());
    var groups=db.queryForList("SELECT w.status,COUNT(*) total,COALESCE(SUM(w.amount4),0) amount4"+tables+where+" GROUP BY w.status",args.toArray()).stream().map(r->new StatusTotal(text(r,"status"),integer(r,"total"),amount(number(r,"amount4")))).toList();
    args.add(size);args.add((long)page*size);
    var rows=db.queryForList(PAYOUT_SELECT+where+" ORDER BY w.requested_at DESC,w.withdrawal_id LIMIT ? OFFSET ?",args.toArray());
    return new PayoutPage(rows.stream().map(MybatisPromotionOperations::mapPayout).toList(),page,size,integer(total,"total"),amount(number(total,"amount4")),groups);
  }
  @Override public Payout adjustIncome(AdjustIncome c) {
    id(c.commandId());id(c.withdrawalId());id(c.adminId());reason(c.reason());
    BigDecimal delta=adjustmentAmount(c.deltaAmount4(),c.expectedVersion());
    String semantic=facts(c.withdrawalId(),delta,c.reason(),c.expectedVersion());
    return transaction(()->{
      if(adminReceipt(c.commandId(),"INCOME_ADJUST",c.withdrawalId(),c.adminId(),semantic)!=null)return payout(c.withdrawalId());
      var withdrawal=one("SELECT * FROM promotion_withdrawal WHERE withdrawal_id=? FOR UPDATE",c.withdrawalId());
      var month=one("SELECT * FROM promotion_payout_month WHERE withdrawal_id=? FOR UPDATE",c.withdrawalId());
      if(withdrawal==null||month==null||month.get("settlement_month")==null||!"WITHDRAWING".equals(text(withdrawal,"status")))throw new IllegalStateException("only monthly payouts awaiting review can be adjusted");
      one("SELECT * FROM promotion_commission_account WHERE promoter_user_id=? FOR UPDATE",withdrawal.get("promoter_user_id"));
      if(integer(month,"adjustment_version")!=c.expectedVersion())throw new IllegalStateException("adjustment version changed");
      BigDecimal base=month.get("base_amount4")==null?number(withdrawal,"amount4"):number(month,"base_amount4");
      if(base.add(delta).signum()<=0)throw new IllegalArgumentException("adjusted amount must be positive");
      long version=c.expectedVersion()+1;Instant now=clock.instant();
      db.update("UPDATE promotion_payout_month SET base_amount4=?,adjustment_amount4=?,adjustment_version=? WHERE withdrawal_id=?",base,delta,version,c.withdrawalId());
      db.update("UPDATE promotion_income_adjustment SET status='SUPERSEDED' WHERE withdrawal_id=? AND status='PENDING'",c.withdrawalId());
      db.update("INSERT INTO promotion_income_adjustment(command_id,withdrawal_id,adjustment_version,delta_amount4,reason,operator_id,status,occurred_at) VALUES(?,?,?,?,?,?,'PENDING',?)",c.commandId(),c.withdrawalId(),version,delta,c.reason(),c.adminId(),time(now));
      adminRecord(c.commandId(),"INCOME_ADJUST",c.withdrawalId(),c.adminId(),semantic,c.reason(),now);return payout(c.withdrawalId());
    });
  }
  private static BigDecimal adjustmentAmount(String value,long version) {
    if(version<0||value==null||!value.matches("-?(?:0|[1-9][0-9]{0,33})(?:\\.[0-9]{1,4})?"))throw new IllegalArgumentException("invalid adjustment");
    BigDecimal delta=new BigDecimal(value).setScale(4);
    if(delta.stripTrailingZeros().scale()>2)throw new IllegalArgumentException("whole cents required");
    return delta;
  }
  private void draftPeriod(String owner,String month) {
    id(owner);monthRange(month);
    if(YearMonth.parse(month).isAfter(YearMonth.now(clock.withZone(ZoneId.of("Asia/Shanghai")))))throw new IllegalArgumentException("future settlement month");
  }
  private void activePromoter(String owner) {
    var promoter=one("SELECT * FROM promoter_account WHERE promoter_user_id=?",owner);
    if(promoter==null||!"ENABLED".equals(text(promoter,"status")))throw new IllegalStateException("promoter is disabled or missing");
  }
  private Map<String,Object> lockIncomeDraft(String owner,String month) {
    // All callers already hold the existing administrator command lock, including first creation.
    if(one("SELECT * FROM promotion_income_draft WHERE promoter_user_id=? AND settlement_month=?",owner,month)==null)
      db.update("INSERT INTO promotion_income_draft(promoter_user_id,settlement_month,withdrawal_id) VALUES(?,?,?)",owner,month,key("draft:",facts(owner,month)));
    return one("SELECT * FROM promotion_income_draft WHERE promoter_user_id=? AND settlement_month=? FOR UPDATE",owner,month);
  }
  private IncomeDraft readIncomeDraft(String owner,String month,int page,int size) {
    var draft=one("SELECT * FROM promotion_income_draft WHERE promoter_user_id=? AND settlement_month=?",owner,month);
    String withdrawal=draft==null?key("draft:",facts(owner,month)):text(draft,"withdrawal_id");
    return new IncomeDraft(owner,month,withdrawal,draft==null?"0.0000":amount(number(draft,"adjustment_amount4")),draft==null?0:integer(draft,"adjustment_version"),adjustmentHistory(withdrawal,page,size));
  }
  @Override public IncomeDraft incomeDraft(String owner,String month,int page,int size) {
    draftPeriod(owner,month);page(page,size);
    return transaction(()->{
      db.queryForList("SELECT version FROM promotion_benefit_policy WHERE version=1 FOR UPDATE");
      activePromoter(owner);return readIncomeDraft(owner,month,page,size);
    });
  }
  @Override public IncomeDraft adjustIncomeDraft(AdjustIncomeDraft c) {
    id(c.commandId());id(c.adminId());draftPeriod(c.promoterUserId(),c.settlementMonth());reason(c.reason());
    BigDecimal delta=adjustmentAmount(c.deltaAmount4(),c.expectedVersion());
    String subject=key("draft:",facts(c.promoterUserId(),c.settlementMonth()));
    String semantic=facts(c.promoterUserId(),c.settlementMonth(),delta,c.reason(),c.expectedVersion());
    return transaction(()->{
      if(adminReceipt(c.commandId(),"INCOME_DRAFT",subject,c.adminId(),semantic)!=null)return readIncomeDraft(c.promoterUserId(),c.settlementMonth(),0,20);
      activePromoter(c.promoterUserId());
      var draft=lockIncomeDraft(c.promoterUserId(),c.settlementMonth());
      if(one("SELECT withdrawal_id FROM promotion_payout_month WHERE promoter_user_id=? AND settlement_month=?",c.promoterUserId(),c.settlementMonth())!=null)
        throw new IllegalStateException("monthly payout already exists");
      if(integer(draft,"adjustment_version")!=c.expectedVersion())throw new IllegalStateException("adjustment version changed");
      var range=monthRange(c.settlementMonth());
      BigDecimal commission=db.queryForObject("SELECT COALESCE(SUM(c.commission_amount4),0) FROM "+INCOME_CONSUMPTIONS+" c WHERE c.promoter_user_id=? AND c.occurred_at>=? AND c.occurred_at<?",BigDecimal.class,c.promoterUserId(),range[0],range[1]);
      if(commission.add(delta).signum()<=0)throw new IllegalArgumentException("adjusted monthly income must be positive");
      long version=c.expectedVersion()+1;Instant now=clock.instant();String withdrawal=text(draft,"withdrawal_id");
      db.update("UPDATE promotion_income_draft SET adjustment_amount4=?,adjustment_version=? WHERE promoter_user_id=? AND settlement_month=?",delta,version,c.promoterUserId(),c.settlementMonth());
      db.update("UPDATE promotion_income_adjustment SET status='SUPERSEDED' WHERE withdrawal_id=? AND status='PENDING'",withdrawal);
      db.update("INSERT INTO promotion_income_adjustment(command_id,withdrawal_id,adjustment_version,delta_amount4,reason,operator_id,status,occurred_at) VALUES(?,?,?,?,?,?,'PENDING',?)",c.commandId(),withdrawal,version,delta,c.reason(),c.adminId(),time(now));
      adminRecord(c.commandId(),"INCOME_DRAFT",subject,c.adminId(),semantic,c.reason(),now);
      return readIncomeDraft(c.promoterUserId(),c.settlementMonth(),0,20);
    });
  }
  @Override public IncomeAdjustmentPage incomeAdjustments(String withdrawalId,int page,int size) {
    id(withdrawalId);page(page,size);payout(withdrawalId);return adjustmentHistory(withdrawalId,page,size);
  }
  private IncomeAdjustmentPage adjustmentHistory(String withdrawalId,int page,int size) {
    long total=db.queryForObject("SELECT COUNT(*) FROM promotion_income_adjustment WHERE withdrawal_id=?",Long.class,withdrawalId);
    var items=db.queryForList("SELECT * FROM promotion_income_adjustment WHERE withdrawal_id=? ORDER BY adjustment_version DESC LIMIT ? OFFSET ?",withdrawalId,size,(long)page*size).stream()
        .map(r->new IncomeAdjustment(text(r,"command_id"),withdrawalId,integer(r,"adjustment_version"),amount(number(r,"delta_amount4")),text(r,"reason"),text(r,"operator_id"),text(r,"status"),Instant.parse(text(r,"occurred_at")))).toList();
    return new IncomeAdjustmentPage(items,page,size,total);
  }
  // Allocation-level consumption netting excludes mixed recharge clawbacks; it is not payout entitlement.
  private static final String INCOME_CONSUMPTIONS="(SELECT l.promoter_user_id,l.allocation_id,l.funding_lot_id,MIN(CASE WHEN l.action_type='COMMISSION_RELEASED' THEN l.created_at END) occurred_at,"
      +"CASE WHEN COUNT(*)=COUNT(l.successful_paid_amount4) THEN SUM(l.successful_paid_amount4) ELSE NULL END paid_amount4,"
      +"SUM(CASE WHEN l.action_type='CONSUMPTION_RELEASE_REVERSED' THEN -l.amount4 ELSE l.amount4 END) commission_amount4 "
      +"FROM promotion_commission_ledger l WHERE l.action_type IN ('COMMISSION_RELEASED','CONSUMPTION_RELEASE_REVERSED') AND l.allocation_id IS NOT NULL "
      +"GROUP BY l.promoter_user_id,l.allocation_id,l.funding_lot_id)";
  private static String[] monthRange(String value) {
    if(value==null||!value.matches("[0-9]{4}-(?:0[1-9]|1[0-2])"))throw new IllegalArgumentException("invalid settlement month");
    YearMonth month=YearMonth.parse(value);ZoneId zone=ZoneId.of("Asia/Shanghai");
    return new String[]{timestamp(month.atDay(1).atStartOfDay(zone).toInstant()),timestamp(month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant())};
  }
  @Override public IncomeConsumptionPage incomeConsumptions(String owner,String month,int page,int size) {
    id(owner);page(page,size);var range=monthRange(month);
    String source=INCOME_CONSUMPTIONS+" c JOIN promotion_funding_benefit f ON f.funding_lot_id=c.funding_lot_id WHERE c.promoter_user_id=? AND c.occurred_at>=? AND c.occurred_at<?";
    long total=db.queryForObject("SELECT COUNT(*) FROM "+source,Long.class,owner,range[0],range[1]);
    var items=db.queryForList("SELECT c.*,f.wallet_owner_user_id,f.order_id,f.cash_rate6 FROM "+source+" ORDER BY c.occurred_at DESC,c.allocation_id LIMIT ? OFFSET ?",owner,range[0],range[1],size,(long)page*size).stream()
        .map(r->new IncomeConsumption(text(r,"allocation_id"),text(r,"wallet_owner_user_id"),text(r,"order_id"),Instant.parse(text(r,"occurred_at")),nullableAmount(r,"paid_amount4"),amount(number(r,"commission_amount4")),text(r,"cash_rate6"))).toList();
    return new IncomeConsumptionPage(items,page,size,total);
  }
  private static String nullableAmount(Map<String,Object> row,String key) {return row.get(key)==null?null:amount(number(row,key));}
  @Override public AgentIncomePage agentIncome(String month,String status,String owner,int page,int size) {
    page(page,size);var range=monthRange(month);
    if(status!=null&&!Set.of("ALL","UNSETTLED","WITHDRAWING","PENDING_PAYOUT","PAID","REJECTED").contains(status))throw new IllegalArgumentException("invalid income status");
    String tables=" FROM promoter_account p LEFT JOIN promotion_commission_account a ON a.promoter_user_id=p.promoter_user_id "
        +"LEFT JOIN (SELECT c.promoter_user_id,COUNT(*) consumption_count,CASE WHEN COUNT(*)=COUNT(c.paid_amount4) THEN SUM(c.paid_amount4) ELSE NULL END consumption_amount4,SUM(c.commission_amount4) commission_amount4 FROM "
        +INCOME_CONSUMPTIONS+" c WHERE c.occurred_at>=? AND c.occurred_at<? GROUP BY c.promoter_user_id) c ON c.promoter_user_id=p.promoter_user_id "
        +"LEFT JOIN promotion_payout_month m ON m.promoter_user_id=p.promoter_user_id AND m.settlement_month=? LEFT JOIN promotion_withdrawal w ON w.withdrawal_id=m.withdrawal_id";
    var args=new ArrayList<Object>(List.of(range[0],range[1],month));String where=" WHERE 1=1";
    if(owner!=null&&!owner.isBlank()){id(owner);where+=" AND p.promoter_user_id=?";args.add(owner);}
    String state="COALESCE(w.status,'UNSETTLED')";
    String consumption="CASE WHEN c.consumption_count IS NULL THEN 0 ELSE c.consumption_amount4 END";
    String finalAmount="COALESCE(w.amount4,0)+CASE WHEN w.status='WITHDRAWING' THEN COALESCE(m.adjustment_amount4,0) ELSE 0 END";
    var groups=db.queryForList("SELECT "+state+" income_status,COUNT(*) total,SUM("+finalAmount+") amount4"+tables+where+" GROUP BY "+state,args.toArray()).stream()
        .map(r->new StatusTotal(text(r,"income_status"),integer(r,"total"),amount(number(r,"amount4")))).toList();
    var summary=one("SELECT CASE WHEN COUNT(*)=COUNT("+consumption+") THEN COALESCE(SUM("+consumption+"),0) ELSE NULL END consumption_total,COALESCE(SUM(c.commission_amount4),0) commission_total,COALESCE(SUM(CASE WHEN w.status='PENDING_PAYOUT' THEN "+finalAmount+" ELSE 0 END),0) pending_total"+tables+where,args.toArray());
    if(status!=null&&!"ALL".equals(status)){where+=" AND "+state+"=?";args.add(status);}
    long total=db.queryForObject("SELECT COUNT(*)"+tables+where,Long.class,args.toArray());
    args.add(size);args.add((long)page*size);
    var items=db.queryForList("SELECT w.*,m.settlement_month,m.transfer_reference,m.paid_at,m.base_amount4,m.adjustment_amount4,m.adjustment_version,p.promoter_user_id income_owner,p.status promoter_status,p.created_at promoter_created_at,a.available4,a.cash_debt4,COALESCE(c.consumption_count,0) consumption_count,"+consumption+" consumption_amount4,COALESCE(c.commission_amount4,0) commission_amount4,"+state+" income_status,"
        +"(SELECT COUNT(*) FROM referral_binding r WHERE r.direct_inviter_user_id=p.promoter_user_id AND r.version=(SELECT MAX(latest.version) FROM referral_binding latest WHERE latest.referred_user_id=r.referred_user_id)) downline_count"
        +tables+where+" ORDER BY p.created_at DESC,p.promoter_user_id LIMIT ? OFFSET ?",args.toArray()).stream()
        .map(r->new AgentIncome(text(r,"income_owner"),text(r,"promoter_status"),Instant.parse(text(r,"promoter_created_at")),integer(r,"downline_count"),integer(r,"consumption_count"),nullableAmount(r,"consumption_amount4"),amount(number(r,"commission_amount4")),amount(number(r,"available4")),amount(number(r,"cash_debt4")),text(r,"income_status"),r.get("withdrawal_id")==null?null:mapPayout(r))).toList();
    return new AgentIncomePage(items,page,size,total,nullableAmount(summary,"consumption_total"),amount(number(summary,"commission_total")),amount(number(summary,"pending_total")),groups);
  }
  @Override public AuditPage audit(String unit,String user,String reference,int page,int size) {
    page(page,size);if(unit!=null&&!Set.of("ALL","CASH","POINT").contains(unit))throw new IllegalArgumentException("invalid audit unit");
    String source="(SELECT CONCAT('cash:',promoter_user_id,':',sequence_no) id,'CASH' unit,promoter_user_id beneficiary,action_type action,amount4,source_event_id,order_id,funding_lot_id,allocation_id,withdrawal_id,'POSTED' status,created_at occurred_at FROM promotion_commission_ledger UNION ALL SELECT d.event_id id,'POINT' unit,d.beneficiary_id beneficiary,d.kind action,d.points4 amount4,d.event_id source_event_id,f.order_id,a.funding_lot_id,d.allocation_id,NULL withdrawal_id,CASE WHEN d.delivered_at IS NULL THEN 'PENDING' ELSE 'POSTED' END status,d.occurred_at FROM promotion_reward_delivery d JOIN promotion_success_allocation a ON a.allocation_id=d.allocation_id JOIN promotion_reward_funding f ON f.funding_lot_id=a.funding_lot_id) entries";
    StringBuilder where=new StringBuilder(" WHERE 1=1");var args=new ArrayList<Object>();
    if(unit!=null&&!"ALL".equals(unit)){where.append(" AND unit=?");args.add(unit);}
    if(user!=null&&!user.isBlank()){id(user);where.append(" AND beneficiary=?");args.add(user);}
    if(reference!=null&&!reference.isBlank()) {if(reference.length()>128)throw new IllegalArgumentException("reference too long");where.append(" AND (order_id=? OR funding_lot_id=? OR allocation_id=? OR withdrawal_id=? OR source_event_id=?)");for(int i=0;i<5;i++)args.add(reference);}
    long count=db.queryForObject("SELECT COUNT(*) FROM "+source+where,Long.class,args.toArray());
    args.add(size);args.add((long)page*size);
    var entries=db.queryForList("SELECT * FROM "+source+where+" ORDER BY occurred_at DESC,id DESC LIMIT ? OFFSET ?",args.toArray()).stream()
        .map(r->new AuditEntry(text(r,"id"),text(r,"unit"),text(r,"beneficiary"),text(r,"action"),amount(number(r,"amount4")),text(r,"source_event_id"),text(r,"order_id"),text(r,"funding_lot_id"),text(r,"allocation_id"),text(r,"withdrawal_id"),text(r,"status"),Instant.parse(text(r,"occurred_at")))).toList();
    return new AuditPage(entries,page,size,count);
  }
  /** Replays ledger sequence order, including debt offsets, for reconciliation and historical reads. */
  public static Map<String,BigDecimal> commissionBalances(List<Map<String,Object>> entries) {
    var expected=new HashMap<String,BigDecimal>();
    for(String dimension:List.of("frozen4","available4","withdrawing4","withdrawn4","cash_debt4"))expected.put(dimension,ZERO);
    var lotFrozen=new HashMap<String,BigDecimal>();
    for(var entry:entries) {
      BigDecimal value=number(entry,"amount4");String lot=text(entry,"funding_lot_id");String action=text(entry,"action_type");
      switch(action) {
        case "COMMISSION_FROZEN" -> {add(expected,"frozen4",value);lotFrozen.merge(lot,value,BigDecimal::add);}
        case "COMMISSION_RATE_CORRECTION" -> {add(expected,"frozen4",value.negate());lotFrozen.merge(lot,value.negate(),BigDecimal::add);}
        case "COMMISSION_RELEASED" -> {
          add(expected,"frozen4",value.negate());lotFrozen.merge(lot,value.negate(),BigDecimal::add);
          BigDecimal offset=value.min(expected.get("cash_debt4"));add(expected,"cash_debt4",offset.negate());add(expected,"available4",value.subtract(offset));
        }
        case "CONSUMPTION_RELEASE_REVERSED" -> {
          add(expected,"frozen4",value);lotFrozen.merge(lot,value,BigDecimal::add);
          BigDecimal recovered=value.min(expected.get("available4"));add(expected,"available4",recovered.negate());add(expected,"cash_debt4",value.subtract(recovered));
        }
        case "RECHARGE_REFUND_CLAWBACK" -> {
          BigDecimal fromFrozen=value.min(lotFrozen.getOrDefault(lot,ZERO));add(expected,"frozen4",fromFrozen.negate());lotFrozen.merge(lot,fromFrozen.negate(),BigDecimal::add);
          BigDecimal released=value.subtract(fromFrozen),recovered=released.min(expected.get("available4"));add(expected,"available4",recovered.negate());add(expected,"cash_debt4",released.subtract(recovered));
        }
        case "INCOME_ADJUSTMENT_INCREASE" -> add(expected,"withdrawing4",value);
        case "INCOME_ADJUSTMENT_DECREASE" -> add(expected,"withdrawing4",value.negate());
        case "WITHDRAWAL_REQUESTED" -> {add(expected,"available4",value.negate());add(expected,"withdrawing4",value);}
        case "WITHDRAWAL_REJECTED","WITHDRAWAL_COMPENSATED" -> {
          add(expected,"withdrawing4",value.negate());BigDecimal offset=value.min(expected.get("cash_debt4"));add(expected,"cash_debt4",offset.negate());add(expected,"available4",value.subtract(offset));
        }
        case "WITHDRAWAL_PAID" -> {add(expected,"withdrawing4",value.negate());add(expected,"withdrawn4",value);}
        default -> throw new IllegalStateException("unrecognized commission ledger action");
      }
    }
    return expected;
  }
  @Override public Reconciliation reconciliation() {
    var snapshot=transaction(()->{
      var differences=new ArrayList<BalanceDifference>();var debts=new ArrayList<Debt>();
      // Lock only this service's account projections to keep the ledger comparison at one committed state.
      for(var account:db.queryForList("SELECT * FROM promotion_commission_account ORDER BY promoter_user_id FOR UPDATE")) {
        String user=text(account,"promoter_user_id");var expected=commissionBalances(db.queryForList("SELECT * FROM promotion_commission_ledger WHERE promoter_user_id=? ORDER BY sequence_no",user));
        for(var e:expected.entrySet()) {
          BigDecimal actual=number(account,e.getKey());if(e.getValue().compareTo(actual)!=0)differences.add(new BalanceDifference(user,e.getKey(),amount(e.getValue()),amount(actual),amount(actual.subtract(e.getValue()))));
        }
        if(number(account,"cash_debt4").signum()>0)debts.add(new Debt(user,"CASH",amount(number(account,"cash_debt4"))));
      }
      long pending=db.queryForObject("SELECT COUNT(*) FROM promotion_reward_delivery WHERE delivered_at IS NULL",Long.class);
      return new Reconciliation(differences,debts,pending,clock.instant(),true,false);
    });
    var debts=new ArrayList<>(snapshot.debts());
    for(var row:db.queryForList("SELECT DISTINCT beneficiary_id FROM promotion_reward_delivery")) {
      String owner=text(row,"beneficiary_id"),debt=currentDebt(owner);
      if(new BigDecimal(debt).signum()>0)debts.add(new Debt(owner,"POINT",debt));
    }
    return new Reconciliation(snapshot.differences(),debts,snapshot.pendingRewardDeliveries(),snapshot.generatedAt(),true,false);
  }
  private static void add(Map<String,BigDecimal> values,String key,BigDecimal delta) {values.merge(key,delta,BigDecimal::add);}
  @Override public Reconciliation preview(String admin,String reason) {
    id(admin);reason(reason);return transaction(()->{
      adminRecord(UUID.randomUUID().toString(),"RECONCILIATION_PREVIEW","reconciliation",admin,"",reason,clock.instant());return reconciliation();
    });
  }
}
