package com.xzwl.cloud.promotion.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xzwl.cloud.promotion.application.production.PromotionService;
import com.xzwl.cloud.promotion.application.production.PromotionStore;
import com.xzwl.cloud.promotion.application.production.PromotionStore.ApplicationDecision;
import com.xzwl.cloud.promotion.application.production.PromotionStore.PayoutAccountStatus;
import com.xzwl.cloud.promotion.application.production.PromotionStore.PromoterStatus;
import com.xzwl.cloud.promotion.application.production.PromotionStore.WithdrawalStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.xzwl.cloud.promotion.infrastructure.persistence.PromotionMappers;
import com.xzwl.cloud.promotion.infrastructure.persistence.PromotionMappers.*;
import com.xzwl.cloud.promotion.infrastructure.persistence.PromotionSqlMapper;
import org.apache.ibatis.session.LocalCacheScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.junit.jupiter.api.AfterEach;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.xzwl.cloud.promotion.application.production.PromotionOperations.*;
import java.time.Clock;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;
class MybatisPromotionOperationsTest {
  private static final Instant T0=Instant.parse("2026-09-01T00:00:00Z");
  private JdbcTemplate sql,mysqlRoot;
  private String mysqlSchema;
  private MyBatisPromotionStore store;
  private PromotionService service;
  private MybatisPromotionOperations operations;

  @Test void customerRateFreezesHistoricalBeneficiaryInsteadOfSpenderAndReversalsReuseIt() {
    chain();
    store.setBenefitClock(Clock.fixed(T0.plusSeconds(4),ZoneOffset.UTC));
    service.changeBenefitRate(new PromotionStore.ChangeBenefitRate("special-b",1,"b","invitationPointsRate","0.200000","调整","admin"));
    store.setBenefitClock(Clock.fixed(T0.plusSeconds(5),ZoneOffset.UTC));
    service.changeBenefitRate(new PromotionStore.ChangeBenefitRate("special-c",2,"c","invitationPointsRate","0.800000","调整","admin"));
    store.setBenefitClock(Clock.fixed(T0.plusSeconds(12),ZoneOffset.UTC));
    service.changeBenefitRate(new PromotionStore.ChangeBenefitRate("later-b",3,"b","invitationPointsRate","0.400000","调整","admin"));
    service.bindReferral(new PromotionStore.BindReferral("rebind-c","c","another",1,T0.plusSeconds(13)));
    paid("special-order","c");
    assertEquals("b",sql.queryForObject("SELECT beneficiary_id FROM promotion_reward_funding WHERE order_id='special-order'",String.class));
    assertEquals(new BigDecimal("0.200000"),sql.queryForObject("SELECT point_rate6 FROM promotion_reward_funding WHERE order_id='special-order'",BigDecimal.class));
    var consume=event("special-use","special-order","c","250.0000");
    operations.settle(consume);operations.settle(consume);
    assertEquals("50.0000",operations.rewards("b",0,20).earnedPoints4());
    var reversal=reverse("special-reversal",consume,"100.0000");
    operations.settle(reversal);operations.settle(reversal);
    assertEquals("30.0000",operations.rewards("b",0,20).earnedPoints4());
    var refund=new PromotionStore.PaidRecharge("special-order","c",new BigDecimal("100.0000"),T0.plusSeconds(10),T0.plusSeconds(11),T0.plusSeconds(50));
    store.projectPaidRecharge(refund);store.projectPaidRecharge(refund);
    assertEquals("0.0000",operations.rewards("b",0,20).earnedPoints4());
  }

  @Test void customerZeroAndDeletedRulesKeepOrderTimeDefaultAndCashExclusion() {
    chain();
    store.setBenefitClock(Clock.fixed(T0.plusSeconds(4),ZoneOffset.UTC));
    service.changeBenefitRate(new PromotionStore.ChangeBenefitRate("zero-b",1,"b","invitationPointsRate","0","调整","admin"));
    store.setBenefitClock(Clock.fixed(T0.plusSeconds(6),ZoneOffset.UTC));
    service.changeBenefitRate(new PromotionStore.ChangeBenefitRate("default-points",2,null,"invitationPointsRate","0.3","调整","admin"));
    store.setBenefitClock(Clock.fixed(T0.plusSeconds(8),ZoneOffset.UTC));
    service.changeBenefitRate(new PromotionStore.ChangeBenefitRate("delete-b",3,"b","invitationPointsRate",null,"删除","admin"));
    long[] times={3,5,9};String[] orders={"before-special","zero-special","after-delete"};String[] rates={"0.100000","0.000000","0.300000"};
    for(int i=0;i<orders.length;i++) {
      var recharge=new PromotionStore.PaidRecharge(orders[i],"c",new BigDecimal("100.0000"),T0.plusSeconds(times[i]),T0.plusSeconds(11),null);
      store.projectPaidRecharge(recharge);store.projectPaidRecharge(recharge);
      assertEquals(new BigDecimal(rates[i]),sql.queryForObject("SELECT point_rate6 FROM promotion_reward_funding WHERE order_id=?",BigDecimal.class,orders[i]));
      operations.settle(event("use-"+orders[i],orders[i],"c","250.0000"));
    }
    assertEquals("100.0000",operations.rewards("b",0,20).earnedPoints4());
    assertEquals(2,sql.queryForObject("SELECT COUNT(*) FROM promotion_reward_delivery",Integer.class));
    paid("cash-only","b");operations.settle(event("cash-use","cash-only","b","250.0000"));
    assertEquals(null,sql.queryForObject("SELECT beneficiary_id FROM promotion_reward_funding WHERE order_id='cash-only'",String.class));
    assertEquals("0.0000",operations.rewards("a",0,20).earnedPoints4());
  }

  @Test void realSuccessReleasesFrozenCashAndCreatesOnlyOrdinaryInviterPoints() {
    chain();paid("b-order","b");paid("c-order","c");paid("d-order","d");
    operations.settle(event("b-use","b-order","b","250.0000"));
    operations.settle(event("c-use","c-order","c","250.0000"));
    operations.settle(event("d-use","d-order","d","250.0000"));
    assertEquals(new BigDecimal("2.5000"),store.getCommission("a").available());
    assertEquals("25.0000",operations.rewards("b",0,20).earnedPoints4());
    assertEquals("25.0000",operations.rewards("c",0,20).earnedPoints4());
    assertEquals("0.0000",operations.rewards("a",0,20).earnedPoints4());
    for(int i=0;i<100;i++)operations.settle(event("c-use","c-order","c","250.0000"));
    assertEquals("25.0000",operations.rewards("b",0,20).earnedPoints4());
    assertEquals(2,operations.pendingRewards(100).size());
    assertTrue(operations.reconciliation().differences().isEmpty());
  }
  @Test void preservesNoneAndHistoricalRatesAndWaitsForPaymentDependency() {
    chain();var event=event("early","early-order","d","250.0000");
    assertThrows(IllegalStateException.class,()->operations.settle(event));
    paid("early-order","d");
    store.changeBenefitRate(new PromotionStore.ChangeBenefitRate("new-rate",1,null,"invitationPointsRate","0.500000","修改","admin"));
    operations.settle(event);
    assertEquals("25.0000",operations.rewards("c",0,20).earnedPoints4());
    paid("none","none-user");
    service.bindReferral(new PromotionStore.BindReferral("late","none-user","c",0,T0.plusSeconds(50)));
    paid("none","none-user");operations.settle(event("none-use","none","none-user","250.0000"));
    assertEquals("25.0000",operations.rewards("c",0,20).earnedPoints4());
  }
  @Test void partialSuccessRefundAndReconsumeNeverReleaseMoreThanOriginalFreeze() {
    chain();paid("b-order","b");
    var success=event("use1","b-order","b","999.9999");operations.settle(success);
    assertEquals(new BigDecimal("9.9999"),store.getCommission("a").available());
    operations.settle(event("use2","b-order","b","0.0001"));
    assertEquals(new BigDecimal("10.0000"),store.getCommission("a").available());
    operations.settle(reverse("rev1",success,"500.0000"));
    assertEquals(new BigDecimal("5.0000"),store.getCommission("a").available());
    assertEquals(new BigDecimal("5.0000"),store.getCommission("a").frozen());
    operations.settle(event("use3","b-order","b","500.0000"));
    assertEquals(new BigDecimal("10.0000"),store.getCommission("a").available());
    assertTrue(operations.reconciliation().differences().isEmpty());
  }
  @Test void portalConsumptionProjectsRefundsIntoOriginalAllocationAndRangeTotals() {
    chain();paid("report-order","b");
    var first=event("report-first","report-order","b","250.0000");
    operations.settle(first);
    operations.settle(event("report-second","report-order","b","250.0000"));
    var refund=reverse("report-refund",first,"100.0000");
    operations.settle(refund);operations.settle(refund);
    var account=store.getCommission("a");
    long ledgerCount=sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger",Long.class);
    // The range selects the original consumption, even though its refund happened later.
    var page=store.portalConsumptions("a",T0,T0.plusSeconds(25),1,1);
    assertEquals(2,page.total());assertEquals("40.0000",page.totalAmount4());
    assertEquals("40.0000",store.portalConsumptions("a",T0,T0.plusSeconds(25),0,1).totalAmount4());
    var item=page.items().getFirst();
    assertEquals("report-first",item.allocationId());assertEquals("15.0000",item.paidAmount4());
    assertEquals("1.5000",item.commissionAmount4());assertEquals(T0.plusSeconds(20),item.occurredAt());
    assertEquals(account,store.getCommission("a"));
    assertEquals(ledgerCount,sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger",Long.class));
    operations.settle(reverse("report-rest",first,"150.0000"));
    var zero=store.portalConsumptions("a",T0,T0.plusSeconds(25),1,1);
    assertEquals(2,zero.total());assertEquals("25.0000",zero.totalAmount4());
    assertEquals("0.0000",zero.items().getFirst().paidAmount4());
    assertEquals("0.0000",zero.items().getFirst().commissionAmount4());
    assertEquals("25.0000",store.portalOverview("a").settledPaidAmount4());
  }
  @Test void monthlyPayoutBelow100PersistsSnapshotAndCannotDoubleReserve() {
    chain();paid("b-order","b");operations.settle(event("used","b-order","b","1000.0000"));
    store.savePortalPayoutAccount(new PromotionStore.SavePayoutAccount("save-account","bank","a","BANK_CARD","测试姓名","6222021234567890123","测试银行",null,0,T0.plusSeconds(90)));
    var command=new CreatePayout("month-create","month-one","a","2026-09","0.0100","bank","admin");
    assertEquals("WITHDRAWING",operations.createPayout(command).status());
    assertEquals("WITHDRAWING",operations.createPayout(command).status());
    assertThrows(RuntimeException.class,()->operations.createPayout(new CreatePayout("another","two","a","2026-09","0.0100","bank","admin")));
    assertEquals(new BigDecimal("0.0100"),store.getCommission("a").withdrawing());
    assertEquals("6222021234567890123",operations.payoutDetails("month-one","admin").accountReference());
    operations.decidePayout(new DecidePayout("approve","month-one",true,null,"admin"));
    operations.completePayout(new CompletePayout("paid","month-one","BANK-REF",T0.plusSeconds(100),"admin"));
    operations.completePayout(new CompletePayout("paid","month-one","BANK-REF",T0.plusSeconds(100),"admin"));
    assertEquals(new BigDecimal("0.0100"),store.getCommission("a").withdrawn());
    assertEquals("0.0100",operations.payouts("PAID",null,"2026-09",0,1).totalAmount4());
    assertTrue(operations.reconciliation().differences().isEmpty());
  }
  @Test void detectsActualProjectionDriftWithoutWritingMoney() {
    chain();paid("b-order","b");
    sql.update("UPDATE promotion_commission_account SET frozen4=frozen4+1 WHERE promoter_user_id='a'");
    var result=operations.reconciliation();assertFalse(result.differences().isEmpty());
    assertEquals(new BigDecimal("11.0000"),store.getCommission("a").frozen());
  }
  @Test void partialRechargeRefundAndConsumptionReversalDoNotClawBackTwice() {
    chain();paid("order","b");var use=event("used","order","b","1000.0000");operations.settle(use);
    store.refundRecharge(new PromotionStore.RefundRecharge("half-refund","recharge:order",new BigDecimal("50.0000"),T0.plusSeconds(25)));
    operations.settle(reverse("reverse-all",use,"1000.0000"));
    assertEquals(new BigDecimal("0.0000"),store.getCommission("a").cashDebt());
    assertEquals(new BigDecimal("5.0000"),store.getCommission("a").frozen());
    store.refundRecharge(new PromotionStore.RefundRecharge("rest-refund","recharge:order",new BigDecimal("50.0000"),T0.plusSeconds(40)));
    assertEquals(new BigDecimal("0.0000"),store.getCommission("a").frozen());
    assertTrue(operations.reconciliation().differences().isEmpty());
  }
  @Test void cannotReleaseLegacySettlementAllocationTwice() {
    chain();paid("order","b");
    store.settleConsumption(new PromotionStore.SettleConsumption("old-settle","used","recharge:order",new BigDecimal("25.0000"),false,T0.plusSeconds(20)));
    assertThrows(IllegalStateException.class,()->operations.settle(event("used","order","b","250.0000")));
    assertEquals(new BigDecimal("2.5000"),store.getCommission("a").available());
  }
  @Test void concurrentSettlementAndFactConflictCannotDuplicateMoney() throws Exception {
    chain();
    sql.update("UPDATE referral_binding SET level2_promoter_user_id='a',cash_promoter_user_id='a',cash_tier='LEVEL_2' WHERE referred_user_id='c'");
    paid("order","c");var event=event("parallel","order","c","250.0000");
    try(var pool=Executors.newFixedThreadPool(8)) {
      var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for(int i=0;i<32;i++)tasks.add(pool.submit(()->operations.settle(event)));
      for(var task:tasks)task.get();
    }
    assertEquals("25.0000",operations.rewards("b",0,20).earnedPoints4());
    assertEquals(new BigDecimal("0.0000"),store.getCommission("a").available());
    assertThrows(IllegalStateException.class,()->operations.settle(event("parallel","order","c","300.0000")));
    assertEquals(1,operations.pendingRewards(100).size());
  }
  @Test void differentLegacyAllocationCannotEraseAccumulatedSettlement() {
    chain();paid("order","b");
    store.settleConsumption(new PromotionStore.SettleConsumption("old","old-allocation","recharge:order",new BigDecimal("25.0000"),false,T0.plusSeconds(20)));
    assertThrows(IllegalStateException.class,()->operations.settle(event("new-allocation","order","b","250.0000")));
    assertEquals(new BigDecimal("2.5000"),store.getCommission("a").available());
  }
  @Test void legacyCallerCannotChangeFundingAfterDurableSettlement() {
    chain();paid("order","b");operations.settle(event("new-allocation","order","b","250.0000"));
    assertThrows(IllegalStateException.class,()->store.settleConsumption(new PromotionStore.SettleConsumption("old","old-allocation","recharge:order",new BigDecimal("25.0000"),false,T0.plusSeconds(20))));
    assertEquals(new BigDecimal("2.5000"),store.getCommission("a").available());
  }
  @Test void fullRechargeRefundMayOvertakeConsumptionReversalWithoutDoubleDebt() {
    chain();paid("order","c");var used=event("used","order","c","1000.0000");operations.settle(used);
    store.projectPaidRecharge(new PromotionStore.PaidRecharge("order","c",new BigDecimal("100.0000"),T0.plusSeconds(10),T0.plusSeconds(11),T0.plusSeconds(25)));
    operations.settle(reverse("reverse",used,"1000.0000"));
    assertEquals("0.0000",operations.rewards("b",0,20).earnedPoints4());
    assertEquals(new BigDecimal("0.0000"),store.getCommission("a").available());
    assertEquals(new BigDecimal("0.0000"),store.getCommission("a").cashDebt());
    assertTrue(operations.reconciliation().differences().isEmpty());
  }
  @Test void reverseBeforeSettleWaitsAndFollowingRechargeRefundClearsFrozen() {
    chain();paid("order","c");var used=event("used","order","c","1000.0000");
    var reverse=reverse("reverse",used,"1000.0000");
    assertThrows(IllegalStateException.class,()->operations.settle(reverse));
    operations.settle(used);operations.settle(reverse);
    store.projectPaidRecharge(new PromotionStore.PaidRecharge("order","c",new BigDecimal("100.0000"),T0.plusSeconds(10),T0.plusSeconds(11),T0.plusSeconds(40)));
    assertEquals("0.0000",operations.rewards("b",0,20).earnedPoints4());
    assertEquals(new BigDecimal("0.0000"),store.getCommission("a").frozen());
    assertEquals(new BigDecimal("0.0000"),store.getCommission("a").cashDebt());
    assertTrue(operations.reconciliation().differences().isEmpty());
  }
  @Test void rejectedMonthCanBeCorrectedAndResubmittedWithoutCreatingAnotherPayout() {
    chain();paid("order","b");operations.settle(event("used","order","b","1000.0000"));
    store.savePortalPayoutAccount(new PromotionStore.SavePayoutAccount("save-account","bank","a","BANK_CARD","测试姓名","6222021234567890123","测试银行",null,0,T0.plusSeconds(90)));
    operations.createPayout(new CreatePayout("create","month","a","2026-09","1.0000","bank","admin"));
    operations.decidePayout(new DecidePayout("reject","month",false,"核对金额","admin"));
    assertEquals(new BigDecimal("10.0000"),store.getCommission("a").available());
    operations.createPayout(new CreatePayout("resubmit","month","a","2026-09","2.0000","bank","admin"));
    assertEquals(new BigDecimal("8.0000"),store.getCommission("a").available());
    assertEquals(new BigDecimal("2.0000"),store.getCommission("a").withdrawing());
    operations.decidePayout(new DecidePayout("approve","month",true,null,"admin"));
    operations.completePayout(new CompletePayout("paid","month","TRANSFER-1",T0.plusSeconds(100),"admin"));
    assertEquals(1,operations.payouts(null,"a","2026-09",0,20).total());
    assertEquals(new BigDecimal("2.0000"),store.getCommission("a").withdrawn());
    assertTrue(operations.reconciliation().differences().isEmpty());
  }
  @Test void adminRebindingHasAuditAndCannotChangeEarlierRechargeSnapshot() {
    chain();paid("old-order","c");
    var change=operations.rebind(new Rebind("admin-rebind","c","another",1,"调整归属","admin"));
    assertEquals("another",change.current().directInviterUserId());
    assertEquals("admin",change.history().getFirst().operatorId());
    assertEquals("调整归属",change.history().getFirst().reason());
    assertEquals(change,operations.rebind(new Rebind("admin-rebind","c","another",1,"调整归属","admin")));
    operations.settle(event("old-consume","old-order","c","250.0000"));
    assertEquals("25.0000",operations.rewards("b",0,20).earnedPoints4());
    assertThrows(IllegalStateException.class,()->operations.rebind(new Rebind("different","c","b",1,"旧版本","admin")));
    assertThrows(IllegalArgumentException.class,()->operations.rebind(new Rebind("self","c","c",2,"自邀","admin")));
  }
  @Test void adjustmentDirectoryPagesOnlyAdminChangesWithPreviousInviters() {
    chain();
    operations.rebind(new Rebind("change-a","c","another",1,"调整归属","admin"));
    operations.rebind(new Rebind("change-z","b","another",1,"业务调整","admin"));
    var first=operations.referralAdjustments(null,0,1);
    assertEquals(2,first.total());assertEquals(1,first.items().size());assertEquals("change-z",first.items().getFirst().commandId());
    var second=operations.referralAdjustments(null,1,1).items().getFirst();
    assertEquals("c",second.userId());assertEquals("b",second.previousInviterId());assertEquals("another",second.inviterId());assertEquals("admin",second.operatorId());assertEquals("调整归属",second.reason());
    assertEquals(1,operations.referralAdjustments("c",0,20).total());assertEquals(0,operations.referralAdjustments("another",0,20).total());
    assertThrows(IllegalArgumentException.class,()->operations.referralAdjustments(null,-1,20));assertThrows(IllegalArgumentException.class,()->operations.referralAdjustments(null,0,101));
    operations.rebind(new Rebind("first-admin","another","a",0,"首次绑定","admin"));
    assertNull(operations.referralAdjustments("another",0,20).items().getFirst().previousInviterId());
  }
  @Test void adjustmentDirectoryUsesChronologicalOrderAcrossIsoFractionPrecisions() {
    chain();
    for(var pair:java.util.List.of(new String[]{"z-second","c","2026-09-02T00:00:00Z"},new String[]{"m-millis","b","2026-09-02T00:00:00.100Z"},new String[]{"a-micros","d","2026-09-02T00:00:00.100001Z"})) {
      var timed=new MybatisPromotionOperations(sql,store,new DataSourceTransactionManager(sql.getDataSource()),Clock.fixed(Instant.parse(pair[2]),ZoneOffset.UTC));
      timed.rebind(new Rebind(pair[0],pair[1],"another",1,"排序核对","admin"));
    }
    assertEquals(java.util.List.of("a-micros","m-millis","z-second"),operations.referralAdjustments(null,0,20).items().stream().map(ReferralAdjustment::commandId).toList());
  }
  @Test void incomeMonthKeepsHistoricalBeneficiaryAndReversalsInOriginalShanghaiMonth() {
    chain();paid("income-order","b");
    store.createAdminPromoter(new PromotionStore.AdminCreatePromoter("open-empty","empty","admin",T0));
    sql.update("UPDATE promoter_account SET status='DISABLED' WHERE promoter_user_id='empty'");
    var used=event("income-used","income-order","b","500.0000");operations.settle(used);
    operations.rebind(new Rebind("move-b","b","another",1,"迁移推荐关系","admin"));
    operations.settle(reverse("income-reverse",used,"100.0000"));
    sql.update("UPDATE promotion_commission_ledger SET created_at='2026-08-31T16:00:00.000000000Z' WHERE source_event_id=?",used.eventId());
    sql.update("UPDATE promotion_commission_ledger SET created_at='2026-10-01T00:00:00.000000000Z' WHERE source_event_id='income-reverse'");
    var page=operations.agentIncome("2026-09",null,null,0,1);
    assertEquals(2,page.total());assertEquals("40.0000",page.totalConsumptionAmount4());assertEquals("4.0000",page.totalCommissionAmount4());
    assertEquals(1,page.items().size());assertEquals(2,page.statusTotals().getFirst().count());
    var rows=operations.incomeConsumptions("a","2026-09",0,20);
    assertEquals(1,rows.total());assertEquals("b",rows.items().getFirst().customerUserId());assertEquals("40.0000",rows.items().getFirst().paidAmount4());
    assertEquals("4.0000",rows.items().getFirst().commissionAmount4());
    assertEquals(0,operations.incomeConsumptions("a","2026-08",0,20).total());
    assertEquals("DISABLED",operations.agentIncome("2026-09",null,"empty",0,20).items().getFirst().promoterStatus());
    sql.update("UPDATE promotion_commission_ledger SET successful_paid_amount4=NULL WHERE source_event_id=?",used.eventId());
    assertNull(operations.agentIncome("2026-09",null,null,0,20).totalConsumptionAmount4());
    assertNull(operations.incomeConsumptions("a","2026-09",0,20).items().getFirst().paidAmount4());
  }
  @Test void adjustmentsAreVersionedAndOnlyApprovalChangesTheReservedIncome() {
    incomePayout();var before=store.getCommission("a");
    var adjustment=new AdjustIncome("adjust-one","income-payout","2.00","人工奖励",0,"admin");
    var pending=operations.adjustIncome(adjustment);assertEquals(before,store.getCommission("a"));
    assertEquals("7.0000",pending.finalAmount4());assertEquals(1,pending.adjustmentVersion());
    assertEquals(1,operations.adjustIncome(adjustment).adjustmentVersion());
    assertThrows(IllegalStateException.class,()->operations.adjustIncome(new AdjustIncome("stale","income-payout","1.00","过期",0,"admin")));
    assertThrows(IllegalStateException.class,()->operations.decidePayout(new DecidePayout("bypass","income-payout",true,null,"admin")));
    assertThrows(IllegalStateException.class,()->operations.decidePayout(new DecidePayout("stale-approve","income-payout",true,null,"admin",1L,"6.0000")));
    var approve=new DecidePayout("income-approve","income-payout",true,null,"admin",1L,"7.0000");
    operations.decidePayout(approve);operations.decidePayout(approve);
    assertEquals(new BigDecimal("5.0000"),store.getCommission("a").available());
    assertEquals(new BigDecimal("7.0000"),store.getCommission("a").withdrawing());
    assertEquals("APPROVED",operations.incomeAdjustments("income-payout",0,20).items().getFirst().status());
    assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger WHERE action_type='INCOME_ADJUSTMENT_INCREASE'",Integer.class));
    assertThrows(IllegalStateException.class,()->operations.adjustIncome(new AdjustIncome("late","income-payout","1.00","太晚",1,"admin")));
    operations.decidePayout(new DecidePayout("compensate","income-payout",false,"撤销发放","admin",1L,"7.0000"));
    assertEquals(new BigDecimal("12.0000"),store.getCommission("a").available());
    var resubmitted=operations.createPayout(new CreatePayout("retry-income","income-payout","a","2026-09","6.0000","bank","admin"));
    assertEquals("0.0000",resubmitted.adjustmentAmount4());assertEquals("6.0000",resubmitted.baseAmount4());
    operations.decidePayout(new DecidePayout("retry-approve","income-payout",true,null,"admin",resubmitted.adjustmentVersion(),"6.0000"));
    operations.completePayout(new CompletePayout("income-paid","income-payout","INCOME-TRANSFER",T0.plusSeconds(100),"admin"));
    assertEquals(new BigDecimal("6.0000"),store.getCommission("a").withdrawn());assertTrue(operations.reconciliation().differences().isEmpty());
  }
  @Test void negativeAdjustmentAndPreApprovalRejectionNeverLoseOrDuplicateIncome() {
    incomePayout();
    assertThrows(IllegalArgumentException.class,()->operations.adjustIncome(new AdjustIncome("precision","income-payout","0.001","精度",0,"admin")));
    assertThrows(IllegalArgumentException.class,()->operations.adjustIncome(new AdjustIncome("zero","income-payout","-5.00","归零",0,"admin")));
    assertThrows(IllegalArgumentException.class,()->operations.adjustIncome(new AdjustIncome("reason","income-payout","1.00"," ",0,"admin")));
    operations.adjustIncome(new AdjustIncome("lower","income-payout","-2.00","扣减",0,"admin"));
    operations.decidePayout(new DecidePayout("reject-adjust","income-payout",false,"驳回","admin",1L,"3.0000"));
    assertEquals(new BigDecimal("10.0000"),store.getCommission("a").available());
    assertEquals("REJECTED",operations.incomeAdjustments("income-payout",0,20).items().getFirst().status());
    var retry=operations.createPayout(new CreatePayout("retry-low","income-payout","a","2026-09","5.0000","bank","admin"));
    var adjustment=operations.adjustIncome(new AdjustIncome("lower-approved","income-payout","-1.00","扣减",retry.adjustmentVersion(),"admin"));
    operations.decidePayout(new DecidePayout("approve-low","income-payout",true,null,"admin",adjustment.adjustmentVersion(),"4.0000"));
    assertEquals(new BigDecimal("5.0000"),store.getCommission("a").available());assertEquals(new BigDecimal("4.0000"),store.getCommission("a").withdrawing());
    assertEquals(new BigDecimal("1.0000"),sql.queryForObject("SELECT amount4 FROM promotion_commission_ledger WHERE action_type='INCOME_ADJUSTMENT_DECREASE'",BigDecimal.class));
    assertTrue(operations.reconciliation().differences().isEmpty());
  }
  @Test void sameVersionCompetingAdjustmentsOnlyAllowOneWinner() throws Exception {
    incomePayout();
    try(var pool=Executors.newFixedThreadPool(2)) {
      var tasks=new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();var start=new CountDownLatch(1);
      for(int i=0;i<2;i++){int n=i;tasks.add(pool.submit(()->{start.await();try{operations.adjustIncome(new AdjustIncome("race-"+n,"income-payout","1.00","竞争",0,"admin"));return true;}catch(IllegalStateException e){return false;}}));}
      start.countDown();int passed=0;for(var task:tasks)if(task.get())passed++;assertEquals(1,passed);
    }
    assertEquals(1,operations.incomeAdjustments("income-payout",0,20).total());assertEquals(new BigDecimal("5.0000"),store.getCommission("a").withdrawing());
  }
  @Test void replacingAdjustmentUsesTotalDeltaAndFailedApprovalRollsBackAllWrites() {
    incomePayout();
    operations.adjustIncome(new AdjustIncome("first-delta","income-payout","1.00","初次",0,"admin"));
    operations.adjustIncome(new AdjustIncome("second-delta","income-payout","2.00","修正总额",1,"admin"));
    assertEquals("SUPERSEDED",operations.incomeAdjustments("income-payout",1,1).items().getFirst().status());
    var before=store.getCommission("a");
    sql.update("UPDATE promotion_withdrawal SET updated_at=? WHERE withdrawal_id='income-payout'",T0.plusSeconds(200).toString());
    var approve=new DecidePayout("after-rollback","income-payout",true,null,"admin",2L,"7.0000");
    assertThrows(IllegalArgumentException.class,()->operations.decidePayout(approve));
    assertEquals(before,store.getCommission("a"));
    assertEquals("5.0000",operations.payouts(null,"a","2026-09",0,20).items().getFirst().amount4());
    assertEquals("PENDING",operations.incomeAdjustments("income-payout",0,20).items().getFirst().status());
    assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger WHERE action_type LIKE 'INCOME_ADJUSTMENT_%'",Integer.class));
    sql.update("UPDATE promotion_withdrawal SET updated_at=? WHERE withdrawal_id='income-payout'",T0.plusSeconds(100).toString());
    operations.decidePayout(approve);
    assertEquals(new BigDecimal("2.0000"),sql.queryForObject("SELECT amount4 FROM promotion_commission_ledger WHERE action_type='INCOME_ADJUSTMENT_INCREASE'",BigDecimal.class));
    assertTrue(operations.reconciliation().differences().isEmpty());
  }
  @Test void mixedRechargeClawbackDoesNotMasqueradeAsConsumptionAndLegacyPayoutRemainsQueryable() {
    incomePayout();
    store.refundRecharge(new PromotionStore.RefundRecharge("half-income-refund","recharge:income-order",new BigDecimal("50.0000"),T0.plusSeconds(110)));
    var page=operations.agentIncome("2026-09","WITHDRAWING","a",0,20);
    assertEquals(1,page.total());assertEquals("100.0000",page.totalConsumptionAmount4());assertEquals("10.0000",page.totalCommissionAmount4());
    assertEquals("0.0000",page.pendingPayoutAmount4());assertEquals("WITHDRAWING",page.items().getFirst().payout().status());
    sql.update("UPDATE promotion_payout_month SET settlement_month=NULL WHERE withdrawal_id='income-payout'");
    assertEquals(1,operations.payouts(null,"a","UNASSIGNED",0,20).total());
    assertEquals(0,operations.payouts(null,"a","2026-09",0,20).total());
    assertEquals("UNSETTLED",operations.agentIncome("2026-09",null,"a",0,20).items().getFirst().status());
    assertThrows(IllegalStateException.class,()->operations.adjustIncome(new AdjustIncome("legacy-adjust","income-payout","1.00","未归属",0,"admin")));
  }
  @Test void legacyDecisionReceiptReplaysWithoutReencodingItsOriginalFacts() {
    incomePayout();
    store.decideWithdrawal(new PromotionStore.DecideWithdrawal("legacy-approve","income-payout","admin",true,null,T0.plusSeconds(100)));
    sql.update("INSERT INTO promotion_admin_operation(command_id,kind,subject_id,admin_id,facts,occurred_at) VALUES(?,'PAYOUT_DECIDE',?,?,?,?)",
        "legacy-approve","income-payout","admin","13:income-payout4:true0:",T0.plusSeconds(100).toString());
    var before=store.getCommission("a");
    long entries=sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger",Long.class);
    assertEquals("PENDING_PAYOUT",operations.decidePayout(new DecidePayout("legacy-approve","income-payout",true,null,"admin")).status());
    assertEquals(before,store.getCommission("a"));
    assertEquals(entries,sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger",Long.class));
    assertEquals("13:income-payout4:true0:",sql.queryForObject("SELECT facts FROM promotion_admin_operation WHERE command_id='legacy-approve'",String.class));
  }
  @Test void pendingPayoutTotalCountsOnlyApprovedAndUnpaidAmounts() {
    incomePayout();
    assertEquals("0.0000",operations.agentIncome("2026-09",null,null,0,20).pendingPayoutAmount4());
    operations.adjustIncome(new AdjustIncome("pending-adjust","income-payout","2.00","人工调整",0,"admin"));
    assertEquals("0.0000",operations.agentIncome("2026-09",null,null,0,20).pendingPayoutAmount4());
    operations.decidePayout(new DecidePayout("pending-approve","income-payout",true,null,"admin",1L,"7.0000"));
    assertEquals("7.0000",operations.agentIncome("2026-09",null,null,0,20).pendingPayoutAmount4());
    operations.completePayout(new CompletePayout("pending-paid","income-payout","PENDING-TRANSFER",T0.plusSeconds(100),"admin"));
    assertEquals("0.0000",operations.agentIncome("2026-09",null,null,0,20).pendingPayoutAmount4());
  }
  @Test void draftChangesStayOffLedgerAndTransferOnlyOnceToPayout() {
    incomeFunds();var before=store.getCommission("a");
    long ledger=sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger",Long.class);
    var empty=operations.incomeDraft("a","2026-09",0,20);
    assertEquals(0,empty.adjustmentVersion());assertEquals("0.0000",empty.adjustmentAmount4());
    var command=new AdjustIncomeDraft("draft-plus","a","2026-09","2.00","奖励",0,"admin");
    var plus=operations.adjustIncomeDraft(command);
    assertEquals(empty.withdrawalId(),plus.withdrawalId());assertEquals("2.0000",plus.adjustmentAmount4());
    assertEquals(1,operations.adjustIncomeDraft(command).adjustmentVersion());
    assertThrows(IllegalStateException.class,()->operations.adjustIncomeDraft(new AdjustIncomeDraft("draft-plus","a","2026-09","3.00","奖励",0,"admin")));
    assertThrows(IllegalStateException.class,()->operations.adjustIncomeDraft(new AdjustIncomeDraft("stale-draft","a","2026-09","1.00","旧版本",0,"admin")));
    var minus=operations.adjustIncomeDraft(new AdjustIncomeDraft("draft-minus","a","2026-09","-1.00","扣减",1,"admin"));
    assertEquals("-1.0000",minus.adjustmentAmount4());assertEquals(2,minus.adjustmentVersion());
    assertEquals(2,operations.incomeDraft("a","2026-09",0,1).adjustments().total());
    assertEquals("SUPERSEDED",operations.incomeDraft("a","2026-09",1,1).adjustments().items().getFirst().status());
    assertEquals(before,store.getCommission("a"));assertEquals(ledger,sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger",Long.class));
    assertThrows(IllegalStateException.class,()->operations.createPayout(new CreatePayout("no-version",minus.withdrawalId(),"a","2026-09","5.0000","bank","admin")));
    assertThrows(IllegalStateException.class,()->operations.createPayout(new CreatePayout("wrong-id","other","a","2026-09","5.0000","bank","admin",2L)));
    assertThrows(IllegalStateException.class,()->operations.createPayout(new CreatePayout("stale-create",minus.withdrawalId(),"a","2026-09","5.0000","bank","admin",1L)));
    var create=new CreatePayout("draft-create",minus.withdrawalId(),"a","2026-09","5.0000","bank","admin",2L);
    var payout=operations.createPayout(create);assertEquals("4.0000",payout.finalAmount4());assertEquals(2,payout.adjustmentVersion());
    assertEquals(payout,operations.createPayout(create));
    assertThrows(IllegalStateException.class,()->operations.createPayout(new CreatePayout("draft-create",minus.withdrawalId(),"a","2026-09","5.0000","bank","admin",1L)));
    assertThrows(IllegalStateException.class,()->operations.adjustIncomeDraft(new AdjustIncomeDraft("after-create","a","2026-09","1.00","太晚",2,"admin")));
    assertEquals(2,operations.incomeAdjustments(payout.id(),0,20).total());
    assertEquals(new BigDecimal("5.0000"),store.getCommission("a").withdrawing());
    var approve=new DecidePayout("draft-approve",payout.id(),true,null,"admin",2L,"4.0000");
    operations.decidePayout(approve);operations.decidePayout(approve);
    assertEquals(new BigDecimal("4.0000"),store.getCommission("a").withdrawing());
    assertEquals(1L,sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger WHERE action_type='INCOME_ADJUSTMENT_DECREASE'",Long.class));
    operations.decidePayout(new DecidePayout("draft-reject",payout.id(),false,"驳回","admin",2L,"4.0000"));
    var reopened=operations.createPayout(new CreatePayout("draft-reopen",payout.id(),"a","2026-09","5.0000","bank","admin"));
    assertEquals("0.0000",reopened.adjustmentAmount4());assertEquals("5.0000",reopened.finalAmount4());
  }
  @Test void draftValidatesMonthEligibilityCentsReasonAndPositiveMonthlyIncome() {
    incomeFunds();
    for(String month:List.of("UNASSIGNED","2026-9","2026-10"))assertThrows(IllegalArgumentException.class,()->operations.incomeDraft("a",month,0,20));
    for(String delta:List.of("0.001","-10.00","-11.00"))assertThrows(IllegalArgumentException.class,()->operations.adjustIncomeDraft(new AdjustIncomeDraft("invalid","a","2026-09",delta,"核对",0,"admin")));
    assertThrows(IllegalArgumentException.class,()->operations.adjustIncomeDraft(new AdjustIncomeDraft("blank","a","2026-09","1.00"," ",0,"admin")));
    assertThrows(IllegalStateException.class,()->operations.incomeDraft("missing","2026-09",0,20));
    sql.update("UPDATE promoter_account SET status='DISABLED' WHERE promoter_user_id='a'");
    assertThrows(IllegalStateException.class,()->operations.adjustIncomeDraft(new AdjustIncomeDraft("disabled","a","2026-09","1.00","核对",0,"admin")));
  }
  @Test void draftAndCreateRaceNeverDropsSavedAdjustment() throws Exception {
    incomeFunds();var draft=operations.incomeDraft("a","2026-09",0,20);var start=new CountDownLatch(1);
    try(var pool=Executors.newFixedThreadPool(2)) {
      var edit=pool.submit(()->{start.await();try{operations.adjustIncomeDraft(new AdjustIncomeDraft("race-draft","a","2026-09","2.00","奖励",0,"admin"));return true;}catch(IllegalStateException e){return false;}});
      var create=pool.submit(()->{start.await();try{operations.createPayout(new CreatePayout("race-create",draft.withdrawalId(),"a","2026-09","5.0000","bank","admin",0L));return true;}catch(IllegalStateException e){return false;}});
      start.countDown();assertNotEquals(edit.get(),create.get());
    }
    var saved=operations.incomeDraft("a","2026-09",0,20);
    if(saved.adjustmentVersion()==1) {
      assertEquals(0,operations.payouts(null,"a","2026-09",0,20).total());
      var created=operations.createPayout(new CreatePayout("after-race",draft.withdrawalId(),"a","2026-09","5.0000","bank","admin",1L));
      assertEquals("7.0000",created.finalAmount4());
    } else assertEquals("5.0000",operations.payouts(null,"a","2026-09",0,20).items().getFirst().finalAmount4());
  }
  private void incomeFunds() {
    chain();paid("income-order","b");operations.settle(event("income-used","income-order","b","1000.0000"));
    store.savePortalPayoutAccount(new PromotionStore.SavePayoutAccount("income-bank","bank","a","BANK_CARD","测试姓名","6222021234567890123","测试银行",null,0,T0.plusSeconds(90)));
  }
  private void incomePayout() {
    chain();paid("income-order","b");operations.settle(event("income-used","income-order","b","1000.0000"));
    store.savePortalPayoutAccount(new PromotionStore.SavePayoutAccount("income-bank","bank","a","BANK_CARD","测试姓名","6222021234567890123","测试银行",null,0,T0.plusSeconds(90)));
    operations.createPayout(new CreatePayout("income-create","income-payout","a","2026-09","5.0000","bank","admin"));
  }
  private void chain() {
    store.createAdminPromoter(new PromotionStore.AdminCreatePromoter("open-a","a","admin",T0));
    service.bindReferral(new PromotionStore.BindReferral("bind-b","b","a",0,T0.plusSeconds(1)));
    service.bindReferral(new PromotionStore.BindReferral("bind-c","c","b",0,T0.plusSeconds(2)));
    service.bindReferral(new PromotionStore.BindReferral("bind-d","d","c",0,T0.plusSeconds(3)));
  }
  private void paid(String order,String owner) {store.projectPaidRecharge(new PromotionStore.PaidRecharge(order,owner,new BigDecimal("100.0000"),T0.plusSeconds(10),T0.plusSeconds(11),null));}
  private ConsumptionSettlement event(String allocation,String order,String owner,String points) {
    return new ConsumptionSettlement("settle:"+allocation,"SETTLED","task-"+allocation,allocation,"recharge:"+order,order,"wallet-"+owner,owner,"100.0000","1000.0000",points,"0.0000",T0.plusSeconds(20));
  }
  private ConsumptionSettlement reverse(String id,ConsumptionSettlement event,String points) {
    return new ConsumptionSettlement(id,"REVERSED",event.settlementId(),event.allocationId(),event.fundingLotId(),event.orderId(),event.walletId(),event.walletOwnerUserId(),event.originalPaidAmount4(),event.originalPoints4(),event.successfulPoints4(),points,T0.plusSeconds(30));
  }
  @BeforeEach
  void setUp() throws Exception {
    String mysqlUrl = System.getenv("PROMOTION_TEST_MYSQL_URL");
    DriverManagerDataSource dataSource;
    if (mysqlUrl != null && !mysqlUrl.isBlank()) {
      if (!mysqlUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/")) {
        throw new IllegalArgumentException("Local schema-free MySQL test URL required");
      }
      String username = System.getenv().getOrDefault("PROMOTION_TEST_MYSQL_USERNAME", "root");
      String password = System.getenv().getOrDefault("PROMOTION_TEST_MYSQL_PASSWORD", "");
      mysqlRoot = new JdbcTemplate(new DriverManagerDataSource(mysqlUrl, username, password));
      mysqlSchema = "promotion_mp_" + UUID.randomUUID().toString().replace("-", "");
      mysqlRoot.execute("CREATE DATABASE " + mysqlSchema);
      dataSource = new DriverManagerDataSource(mysqlUrl + mysqlSchema, username, password);
    } else {
      dataSource = new DriverManagerDataSource(
          "jdbc:h2:mem:promotion;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
    }
    sql = new JdbcTemplate(dataSource);
    if (mysqlRoot == null) sql.execute("DROP ALL OBJECTS");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    for (Class<?> mapper : PromotionMappers.class.getDeclaredClasses()) {
      configuration.addMapper(mapper);
    }
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(dataSource);
    factory.setConfiguration(configuration);
    factory.setMapperLocations(new PathMatchingResourcePatternResolver()
        .getResources("classpath*:mapper/PromotionSqlMapper.xml"));
    var pagination = new MybatisPlusInterceptor();
    pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    factory.setPlugins(pagination);
    var session = new SqlSessionTemplate(factory.getObject());
    store = new MyBatisPromotionStore(
        session.getMapper(Eligibilities.class),
        session.getMapper(Applications.class),
        session.getMapper(Promoters.class),
        session.getMapper(Receipts.class),
        session.getMapper(Referrals.class),
        session.getMapper(Policies.class),
        session.getMapper(Accounts.class),
        session.getMapper(FundingBenefits.class),
        session.getMapper(Ledger.class),
        session.getMapper(Payouts.class),
        session.getMapper(Withdrawals.class),
        session.getMapper(PromotionSqlMapper.class), new DataSourceTransactionManager(dataSource));
    service = new PromotionService(store);
    store.setAdminUserDirectory(id->true);
    store.setLiveApplicants(id->new PromotionStore.LiveApplicant(id,"PERSONAL",true,T0,1,id,"138****1234"));
    operations=new MybatisPromotionOperations(sql,store,new DataSourceTransactionManager(dataSource),Clock.fixed(T0.plusSeconds(100),ZoneOffset.UTC));
    store.setPaidRechargeProjection(operations::recordRecharge);
    operations.setRewardDebtLookup(owner->"0.0000");
  }

  @AfterEach
  void dropTestSchema() {
    if (mysqlRoot != null && mysqlSchema != null) {
      mysqlRoot.execute("DROP DATABASE " + mysqlSchema);
    }
  }

}
