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

class MyBatisPromotionStoreTest {
  private static final Instant T0 = Instant.parse("2026-09-01T00:00:00.123456789Z");
  private JdbcTemplate sql;
  private PromotionService service;
  private MyBatisPromotionStore store;
  private JdbcTemplate mysqlRoot;
  private String mysqlSchema;
  private java.util.function.LongConsumer beforeInvitationInsert = number -> {};

  @Test void liveInvitationAndApplicationUseOneOwnerAndSurviveRetries() {
    store.setLiveApplicants(owner->new PromotionStore.LiveApplicant(owner,"PERSONAL",true,T0,1,owner,"138****1234"));
    var a=service.participation("inviter",0,20);
    assertEquals(a.invitationCode(),service.participation("inviter",0,20).invitationCode());
    assertThrows(IllegalArgumentException.class,()->service.bindInvitation("inviter",a.invitationCode()));
    var bound=service.bindInvitation("friend",a.invitationCode());
    assertEquals(bound,service.bindInvitation("friend",a.invitationCode()));
    var other=service.participation("other",0,20);
    assertThrows(IllegalStateException.class,()->service.bindInvitation("friend",other.invitationCode()));
    assertThrows(IllegalArgumentException.class,()->service.bindInvitation("inviter",service.participation("friend",0,20).invitationCode()));
    assertEquals(1,service.participation("inviter",0,1).directInvites().total());
    assertEquals(0,service.participation("inviter",1,1).directInvites().items().size());
    var request=new PromotionStore.SubmitApplication("live-request","live-app","inviter","申请代理","社群",true,T0.plusSeconds(1));
    var app=service.submitApplication(request);
    assertEquals(app,service.submitApplication(new PromotionStore.SubmitApplication("live-request","live-app","inviter","申请代理","社群",true,T0.plusSeconds(3))));
    assertThrows(IllegalStateException.class,()->service.submitApplication(new PromotionStore.SubmitApplication("second","second-app","inviter","申请代理","社群",true,T0.plusSeconds(4))));
    service.reviewApplication(new PromotionStore.ReviewApplication("reject-live","live-app","admin",ApplicationDecision.REJECT,"补充渠道",T0.plusSeconds(5)));
    assertEquals("REJECTED",service.participation("inviter",0,20).application().status());
    service.submitApplication(new PromotionStore.SubmitApplication("reapply","new-app","inviter","补充申请","社群",true,T0.plusSeconds(6)));
    service.reviewApplication(new PromotionStore.ReviewApplication("approve-live","new-app","admin",ApplicationDecision.APPROVE,null,T0.plusSeconds(7)));
    assertEquals(a.invitationCode(),service.getPromoter("inviter").invitationCode());
    assertEquals(1,service.listPromoters("inviter",0,20).total());
    store.setLiveApplicants(owner->new PromotionStore.LiveApplicant(owner,"PERSONAL",false,T0,2,owner,"138****1234"));
    assertThrows(IllegalStateException.class,()->service.participation("inviter",0,20));
    assertThrows(IllegalStateException.class,()->service.bindInvitation("friend2",a.invitationCode()));
  }

  @Test void unactivatedUserCanApplyWithoutInvitationEligibility() {
    store.setLiveApplicants(owner->new PromotionStore.LiveApplicant(owner,"UNSET",true,T0,1,owner,"138****1234"));
    assertEquals(false,service.participation("new-user",0,20).eligible());
    assertEquals(null,service.participation("new-user",0,20).invitationCode());
    var request=new PromotionStore.SubmitApplication("new-request","new-application","new-user","申请推广","社群",true,T0.plusSeconds(1));
    var application=service.submitApplication(request);
    assertEquals("PENDING",application.status());assertEquals("UNSET",application.accountType());
    assertEquals(application,service.submitApplication(request));
    assertEquals(false,service.participation("new-user",0,20).eligible());
    assertEquals(null,service.participation("new-user",0,20).invitationCode());
    assertEquals("PENDING",service.participation("new-user",0,20).application().status());
    assertThrows(IllegalStateException.class,()->service.submitApplication(new PromotionStore.SubmitApplication("duplicate","duplicate-app","new-user","申请推广","社群",true,T0.plusSeconds(2))));
    store.setLiveApplicants(owner->new PromotionStore.LiveApplicant(owner,"UNSET",false,T0,2,owner,"138****1234"));
    assertThrows(IllegalStateException.class,()->service.submitApplication(new PromotionStore.SubmitApplication("disabled","disabled-app","disabled-user","申请推广","社群",true,T0.plusSeconds(3))));
  }

  @Test void enabledAgentCanInviteBeforeFirstRechargeWithoutOpeningOrdinaryUnsetInvites() {
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"UNSET",true,T0,1,owner,"138****1234"));
    store.setAdminUserDirectory(owner -> true);
    var agent = service.createAdminPromoter(adminCreate("create-unset", "unset-agent", T0));
    assertEquals(agent.invitationCode(), service.participation("unset-agent",0,20).invitationCode());
    assertEquals(false, service.participation("ordinary",0,20).eligible());
    var bound = service.bindInvitation("new-user", agent.invitationCode());
    assertEquals("unset-agent", bound.directInviterUserId());
    assertEquals("unset-agent", bound.level1PromoterUserId());
    assertEquals(bound, service.bindInvitation("new-user", agent.invitationCode()));
    assertThrows(IllegalArgumentException.class, () -> service.bindInvitation("unset-agent",agent.invitationCode()));
    var second = service.createAdminPromoter(adminCreate("second-unset", "other-agent", T0));
    assertThrows(IllegalStateException.class, () -> service.bindInvitation("new-user",second.invitationCode()));
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"UNSET",!owner.equals("unset-agent"),T0,2,owner,"138****1234"));
    assertThrows(IllegalStateException.class, () -> service.bindInvitation("another-user",agent.invitationCode()));
  }


  @Test void fourCharacterCodesUpgradeExistingAgentsAndKeepLegacyLinks() {
    var source = sql.getDataSource();
    Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
    Flyway.configure().dataSource(source).locations("classpath:db/migration").target("3").load().migrate();
    String legacy = "PCF06DCA2ED0A380";
    sql.update("INSERT INTO promoter_account(promoter_user_id,invitation_code,status,created_at,updated_at) VALUES(?,?,?,?,?)", "legacy-agent",legacy,"ENABLED",T0.toString(),T0.toString());
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"PERSONAL",true,T0,1,owner,"138****1234"));
    String code = service.getPromoter("legacy-agent").invitationCode();
    org.junit.jupiter.api.Assertions.assertTrue(code.matches("[A-Z0-9]{4}"));
    assertEquals(code,service.participation("legacy-agent",0,20).invitationCode());
    assertEquals(code,service.listPromoters(null,0,20).items().getFirst().invitationCode());
    assertEquals(legacy,sql.queryForObject("SELECT invitation_code FROM promotion_invitation WHERE user_id='legacy-agent'",String.class));
    var oldBinding = service.bindInvitation("old-link-user",legacy);
    assertEquals("legacy-agent",oldBinding.directInviterUserId());
    assertEquals(oldBinding,service.bindInvitation("old-link-user",code));
    assertEquals("legacy-agent",service.bindInvitation("short-link-user",code).directInviterUserId());
    long allocated = sql.queryForObject("SELECT COUNT(*) FROM promotion_invitation",Long.class);
    for(int i=0;i<20;i++) assertEquals(code,service.getPromoter("legacy-agent").invitationCode());
    assertEquals(allocated,sql.queryForObject("SELECT COUNT(*) FROM promotion_invitation",Long.class));
    String next = service.participation("another-user",0,20).invitationCode();
    org.junit.jupiter.api.Assertions.assertNotEquals(code,next);
    assertEquals(allocated+1,sql.queryForObject("SELECT COUNT(*) FROM promotion_invitation",Long.class));
  }

  @Test void fourCharacterCodesRemainUniqueAndNeverTruncateAtCapacity() throws Exception {
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"PERSONAL",true,T0,1,owner,"138****1234"));
    try(var pool=Executors.newFixedThreadPool(2)) {
      var first=pool.submit(() -> service.participation("first",0,20).invitationCode());
      var second=pool.submit(() -> service.participation("second",0,20).invitationCode());
      org.junit.jupiter.api.Assertions.assertNotEquals(first.get(),second.get());
    }
    sql.update("UPDATE promotion_invitation SET short_id=? WHERE user_id='first'",1679615L);
    assertEquals("ZZZZ",service.participation("first",0,20).invitationCode());
    sql.update("UPDATE promotion_invitation SET short_id=? WHERE user_id='first'",1679616L);
    assertThrows(IllegalStateException.class,()->service.participation("first",0,20));
  }

  @Test void randomInvitationRetriesDatabaseCollisionAndKeepsItsOwner() {
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"PERSONAL",true,T0,1,owner,"138****1234"));
    var attempts = new java.util.ArrayList<Long>();
    beforeInvitationInsert = number -> {
      attempts.add(number);
      if (attempts.size() == 1) sql.update(
          "INSERT INTO promotion_invitation(user_id,invitation_code,short_id) VALUES(?,?,?)",
          "occupied", "LEGACY-OCCUPIED", number);
    };
    String code = service.participation("new-owner",0,20).invitationCode();
    org.junit.jupiter.api.Assertions.assertTrue(attempts.size() >= 2);
    assertEquals(attempts.getLast(),Long.valueOf(code,36));
    assertEquals(attempts.getFirst(),sql.queryForObject("SELECT short_id FROM promotion_invitation WHERE user_id='occupied'",Long.class));
    assertEquals("LEGACY-OCCUPIED",sql.queryForObject("SELECT invitation_code FROM promotion_invitation WHERE user_id='occupied'",String.class));
    int count = attempts.size();
    assertEquals(code,service.participation("new-owner",0,20).invitationCode());
    assertEquals(count,attempts.size());
    assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> sql.update(
        "INSERT INTO promotion_invitation(user_id,invitation_code,short_id) VALUES(?,?,?)",
        "duplicate", "LEGACY-DUPLICATE", Long.parseLong(code,36)));
    assertEquals("new-owner",service.bindInvitation("friend",code).directInviterUserId());
  }

  @Test void randomInvitationStopsAfterBoundedCollisionsAndRollsBack() {
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"PERSONAL",true,T0,1,owner,"138****1234"));
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    beforeInvitationInsert = number -> {
      attempts.incrementAndGet();
      sql.update("INSERT INTO promotion_invitation(user_id,invitation_code,short_id) VALUES(?,?,?) ON DUPLICATE KEY UPDATE user_id=user_id",
          "occupied-"+number,"LEGACY-"+number,number);
    };
    var failure = assertThrows(IllegalStateException.class, () -> service.participation("new-owner",0,20));
    assertEquals("could not allocate a unique invitation code after 32 attempts",failure.getMessage());
    assertEquals(32,attempts.get());
    assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM promotion_invitation",Integer.class));
  }

  @Test void concurrentInvitationAllocationForSameOwnerReturnsOneCode() throws Exception {
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"PERSONAL",true,T0,1,owner,"138****1234"));
    try(var pool=Executors.newFixedThreadPool(4)) {
      var start = new CountDownLatch(1);
      var tasks = java.util.stream.IntStream.range(0,4).mapToObj(i -> pool.submit(() -> {
        start.await();
        return service.participation("same-owner",0,20).invitationCode();
      })).toList();
      start.countDown();
      String code = tasks.getFirst().get();
      for(var task:tasks) assertEquals(code,task.get());
    }
    assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM promotion_invitation",Integer.class));
  }

  @Test void paidRechargeDeliveryFreezesOnlyDirectAgentCashAndReplaysWithoutDrift() throws Exception {
    approvePromoter("p1");
    service.bindReferral(new PromotionStore.BindReferral("b1","first","p1",0,T0.plusSeconds(2)));
    service.bindReferral(new PromotionStore.BindReferral("b2","second","first",0,T0.plusSeconds(3)));
    var first=new PromotionStore.PaidRecharge("paid-first","first",new BigDecimal("100.0000"),T0.plusSeconds(4),T0.plusSeconds(5),null);
    var second=new PromotionStore.PaidRecharge("paid-second","second",new BigDecimal("100.0000"),T0.plusSeconds(4),T0.plusSeconds(5),null);
    assertEquals("NONE",service.projectPaidRecharge(second));
    try(var pool=Executors.newFixedThreadPool(4)) {
      var tasks=java.util.stream.IntStream.range(0,8).mapToObj(i->pool.submit(()->service.projectPaidRecharge(first))).toList();
      for(var task:tasks) assertEquals("RESOLVED",task.get());
    }
    assertEquals("100.0000",service.portalOverview("p1").paidAmount4());
    assertEquals(new BigDecimal("10.0000"),service.getCommission("p1").frozen());
    assertEquals(1,service.listCommissionEntries("p1",0,20).total());
    assertThrows(IllegalStateException.class,()->service.projectPaidRecharge(new PromotionStore.PaidRecharge("paid-first","first",new BigDecimal("101.0000"),T0.plusSeconds(4),T0.plusSeconds(5),null)));
    // A later referral must never replace the original order-time owner.
    approvePromoter("p2");
    service.bindReferral(new PromotionStore.BindReferral("rebind","first","p2",1,T0.plusSeconds(10)));
    assertEquals("RESOLVED",service.projectPaidRecharge(first));
    assertEquals(new BigDecimal("0.0000"),service.getCommission("p2").frozen());
    var refunded=new PromotionStore.PaidRecharge("paid-first","first",first.amount4(),first.createdAt(),first.paidAt(),T0.plusSeconds(20));
    service.projectPaidRecharge(refunded);service.projectPaidRecharge(refunded);service.projectPaidRecharge(first);
    assertEquals(new BigDecimal("0.0000"),service.getCommission("p1").frozen());
    assertEquals(2,service.listCommissionEntries("p1",0,20).total());
  }

  @Test void paidRechargeNoneAndRefundFirstRemainIdempotent() throws Exception {
    approvePromoter("p1");
    var none=new PromotionStore.PaidRecharge("none","customer",new BigDecimal("100"),T0,T0.plusSeconds(5),null);
    try(var pool=Executors.newFixedThreadPool(4)) {
      var tasks=java.util.stream.IntStream.range(0,8).mapToObj(i->pool.submit(()->service.projectPaidRecharge(none))).toList();
      for(var task:tasks) assertEquals("NONE",task.get());
    }
    assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM promotion_command_receipt WHERE command_id='paid-credit:none'",Integer.class));
    service.bindReferral(new PromotionStore.BindReferral("later","customer","p1",0,T0.plusSeconds(2)));
    assertEquals("NONE",service.projectPaidRecharge(none));
    var beforeBinding=new PromotionStore.PaidRecharge("before","customer",new BigDecimal("100"),T0,T0.plusSeconds(5),null);
    assertEquals("NONE",service.projectPaidRecharge(beforeBinding));
    var refunded=new PromotionStore.PaidRecharge("refund-first","customer",new BigDecimal("100"),T0.plusSeconds(3),T0.plusSeconds(4),T0.plusSeconds(6));
    service.projectPaidRecharge(refunded);service.projectPaidRecharge(refunded);
    assertEquals(new BigDecimal("0.0000"),service.getCommission("p1").frozen());
    assertEquals(2,service.listCommissionEntries("p1",0,20).total());
    service.changeBenefitRate(rateChange("later-policy",1,null,"defaultLevel1CashRate","0.2","后续配置","admin",T0.plusSeconds(10)));
    assertEquals("RESOLVED",service.projectPaidRecharge(new PromotionStore.PaidRecharge("historical","customer",new BigDecimal("100"),T0.plusSeconds(3),T0.plusSeconds(4),null)));
    assertEquals(new BigDecimal("10.0000"),service.getCommission("p1").frozen());
    assertEquals("RESOLVED",service.projectPaidRecharge(refunded));
  }

  @Test void paidRechargeRejectsPreexistingFundingWithoutItsDeliveryReceipt() {
    approvePromoter("p1");
    service.bindReferral(new PromotionStore.BindReferral("bound","customer","p1",0,T0.plusSeconds(2)));
    service.freezeRecharge(new PromotionStore.FreezeRecharge("legacy-event","legacy-order","recharge:legacy-order","customer",1,1,new BigDecimal("100"),T0.plusSeconds(5)));
    var conflicting=new PromotionStore.PaidRecharge("legacy-order","other",new BigDecimal("100"),T0,T0.plusSeconds(5),T0.plusSeconds(6));
    assertThrows(IllegalStateException.class,()->service.projectPaidRecharge(conflicting));
    assertEquals(new BigDecimal("10.0000"),service.getCommission("p1").frozen());
    assertEquals(1,service.listCommissionEntries("p1",0,20).total());
    assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM promotion_command_receipt WHERE command_id='paid-credit:legacy-order'",Integer.class));
  }

  @Test void customerOverridesAcceptRealOrdinaryUsersAndCopyAcrossEveryPolicy() {
    approvePromoter("agent");
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"PERSONAL",true,T0,1,"客户"+owner,"138****1234"));
    var change=rateChange("customer-zero",1,"customer","invitationPointsRate","0","调整","admin",T0.plusSeconds(1));
    assertEquals(2,service.changeBenefitRate(change).version());
    assertEquals(2,service.changeBenefitRate(change).version());
    assertThrows(IllegalStateException.class,()->service.changeBenefitRate(rateChange("customer-zero",1,"customer","invitationPointsRate","0.2","调整","admin",T0.plusSeconds(1))));
    service.changeBenefitRate(rateChange("agent-special",2,"agent","level1CashRate","0.2","调整","admin",T0.plusSeconds(2)));
    service.changeBenefitRate(rateChange("default-change",3,null,"invitationPointsRate","0.3","调整","admin",T0.plusSeconds(3)));
    assertEquals(new BigDecimal("0.000000"),sql.queryForObject("SELECT point_rate6 FROM promotion_customer_point_override WHERE policy_version=4 AND customer_user_id='customer'",BigDecimal.class));
    assertEquals(new BigDecimal("0.200000"),sql.queryForObject("SELECT level1_cash_rate6 FROM promotion_benefit_override WHERE policy_version=4 AND promoter_user_id='agent'",BigDecimal.class));
    assertThrows(IllegalStateException.class,()->service.changeBenefitRate(rateChange("stale-customer",2,"customer","invitationPointsRate","0.2","调整","admin",T0.plusSeconds(4))));
    service.changeBenefitRate(rateChange("delete-customer",4,"customer","invitationPointsRate",null,"删除","admin",T0.plusSeconds(4)));
    assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM promotion_customer_point_override WHERE policy_version=5",Integer.class));
    assertEquals(1,sql.queryForObject("SELECT COUNT(*) FROM promotion_benefit_override WHERE policy_version=5",Integer.class));
    var history=service.benefitSettingsHistory();
    assertEquals("CUSTOMER_OVERRIDE",history.getFirst().scope());
    assertEquals("invitationPointsRate",history.getFirst().field());
    assertEquals("0.000000",history.getFirst().beforeRate());
    assertEquals(null,history.getFirst().afterRate());
    assertEquals(null,history.get(3).beforeRate());
    assertEquals("0.000000",history.get(3).afterRate());
    assertEquals(null,history.getLast().beforeRate());
    assertEquals(null,history.getLast().field());
    sql.update("UPDATE promotion_benefit_policy SET changed_field=NULL WHERE version IN (3,4)");
    history=service.benefitSettingsHistory();
    assertEquals("invitationPointsRate",history.get(1).field());
    assertEquals("0.100000",history.get(1).beforeRate());
    assertEquals("0.300000",history.get(1).afterRate());
    assertEquals("level1CashRate",history.get(2).field());
    assertEquals(null,history.get(2).beforeRate());
    assertEquals("0.200000",history.get(2).afterRate());
  }

  @Test void customerHistoryExcludesCashOverridesAndAgentDateTracksLevelOne() {
    approvePromoter("agent");
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"PERSONAL",true,T0,1,owner,"138****1234"));
    service.changeBenefitRate(rateChange("agent-level-one",1,"agent","level1CashRate","0.2","调整","admin",T0.plusSeconds(1)));
    service.changeBenefitRate(rateChange("agent-level-two",2,"agent","level2CashRate","0.3","调整","admin",T0.plusSeconds(2)));
    sql.update("UPDATE promotion_benefit_policy SET changed_field=NULL WHERE version IN (2,3)");
    var agentEffectiveAt=Instant.parse(service.benefitSettings().promoters().getFirst().effectiveAt());
    service.setPromoterStatus(new PromotionStore.ChangePromoterStatus("disable-agent-rule","agent",PromoterStatus.DISABLED,"admin",T0.plusSeconds(3)));
    service.changeBenefitRate(rateChange("ordinary-agent",3,"agent","invitationPointsRate","0.4","调整","admin",T0.plusSeconds(4)));
    var customerHistory=service.benefitSettingsHistory().getFirst();
    assertEquals("CUSTOMER_OVERRIDE",customerHistory.scope());
    assertEquals("0.400000",customerHistory.invitationPointsRate());
    org.junit.jupiter.api.Assertions.assertAll(
        ()->assertEquals(T0.plusSeconds(1),agentEffectiveAt),
        ()->assertEquals(null,customerHistory.level1CashRate()),
        ()->assertEquals(null,customerHistory.level2CashRate()));
    assertEquals(T0.plusSeconds(4),Instant.parse(service.benefitSettings().customers().getFirst().effectiveAt()));
  }

  @Test void oldNoOpHistoryUsesRecordedFieldWithEqualBeforeAndAfterRates() {
    service.changeBenefitRate(rateChange("same-rate",1,null,"invitationPointsRate","0.1","维持比例","admin",T0));
    sql.update("UPDATE promotion_benefit_policy SET changed_field=NULL WHERE version=2");
    var history=service.benefitSettingsHistory().getFirst();
    assertEquals("invitationPointsRate",history.field());
    assertEquals("0.100000",history.beforeRate());assertEquals("0.100000",history.afterRate());
  }

  @Test void customerEligibilityUsesLiveIdentityButRetainedRulesCanBeDeletedAfterIdentityChanges() {
    approvePromoter("agent");
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"PERSONAL",true,T0,1,"真实昵称","138****1234"));
    assertThrows(IllegalArgumentException.class,()->service.changeBenefitRate(rateChange("agent-points",1,"agent","invitationPointsRate","0.2","调整","admin",T0)));
    for(String type:java.util.List.of("UNSET","ADMIN","NORMAL_ADMIN")) {
      store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,type,true,T0,1,"真实昵称","138****1234"));
      assertThrows(IllegalArgumentException.class,()->service.changeBenefitRate(rateChange("invalid-"+type,1,"customer","invitationPointsRate","0.2","调整","admin",T0)));
    }
    store.setLiveApplicants(owner -> null);
    assertThrows(IllegalStateException.class,()->service.changeBenefitRate(rateChange("missing-customer",1,"missing","invitationPointsRate","0.2","调整","admin",T0)));
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant("different","PERSONAL",true,T0,1,"真实昵称","138****1234"));
    assertThrows(IllegalStateException.class,()->service.changeBenefitRate(rateChange("mismatched-customer",1,"customer","invitationPointsRate","0.2","调整","admin",T0)));
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"PERSONAL",false,T0,1,"真实昵称","138****1234"));
    assertThrows(IllegalArgumentException.class,()->service.changeBenefitRate(rateChange("disabled-customer",1,"customer","invitationPointsRate","0.2","调整","admin",T0)));
    long version=1;
    for(String type:java.util.List.of("PERSONAL","ENTERPRISE","API")) {
      store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,type,true,T0,1,"真实昵称","138****1234"));
      service.changeBenefitRate(rateChange("valid-"+type,version++,"customer","invitationPointsRate","0.123456","调整","admin",T0.plusSeconds(version)));
    }
    var customer=service.benefitSettings().customers().getFirst();
    assertEquals("真实昵称",customer.name());assertEquals("138****1234",customer.maskedPhone());
    assertEquals("0.123456",customer.invitationPointsRate());assertEquals("API",customer.accountType());
    assertEquals(T0.plusSeconds(4),Instant.parse(customer.effectiveAt()));
    assertEquals(true,customer.enabled());assertEquals(false,customer.cashAgent());
    assertEquals("138****1234",service.benefitSettings().promoters().getFirst().maskedPhone());
    assertEquals(true,service.benefitSettings().promoters().getFirst().enabled());
    assertThrows(IllegalArgumentException.class,()->service.changeBenefitRate(rateChange("precision-customer",4,"customer","invitationPointsRate","0.1234567","调整","admin",T0.plusSeconds(5))));
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"PERSONAL",false,T0,2,"真实昵称","138****1234"));
    assertEquals(false,service.benefitSettings().customers().getFirst().enabled());
    assertEquals(false,service.benefitSettings().promoters().getFirst().enabled());
    service.changeBenefitRate(rateChange("delete-disabled",4,"customer","invitationPointsRate",null,"删除","admin",T0.plusSeconds(6)));
    assertEquals(0,service.benefitSettings().customers().size());
    store.setLiveApplicants(owner -> new PromotionStore.LiveApplicant(owner,"PERSONAL",true,T0,3,"真实昵称","138****1234"));
    service.changeBenefitRate(rateChange("add-again",5,"customer","invitationPointsRate","0.2","调整","admin",T0.plusSeconds(7)));
    store.setAdminUserDirectory(owner -> true);
    service.createAdminPromoter(new PromotionStore.AdminCreatePromoter("promote-customer","customer","admin",T0.plusSeconds(8)));
    assertEquals(true,service.benefitSettings().customers().getFirst().cashAgent());
    assertThrows(IllegalArgumentException.class,()->service.changeBenefitRate(rateChange("update-promoted",6,"customer","invitationPointsRate","0.3","调整","admin",T0.plusSeconds(9))));
    service.changeBenefitRate(rateChange("delete-promoted",6,"customer","invitationPointsRate",null,"删除","admin",T0.plusSeconds(9)));
    assertEquals(0,service.benefitSettings().customers().size());
    service.setPromoterStatus(new PromotionStore.ChangePromoterStatus("disable-promoted","customer",PromoterStatus.DISABLED,"admin",T0.plusSeconds(10)));
    service.changeBenefitRate(rateChange("disabled-agent-points",7,"customer","invitationPointsRate","0.2","调整","admin",T0.plusSeconds(11)));
    assertEquals(false,service.benefitSettings().customers().getFirst().cashAgent());
    assertEquals(false,service.benefitSettings().promoters().stream().filter(p->p.id().equals("customer")).findFirst().orElseThrow().enabled());
  }

  @Test void savedBenefitRatesSelectOrderTimeAndPerTierOverride() {
    approvePromoter("p1");
    store.setLiveApplicants(owner->new PromotionStore.LiveApplicant(owner,"PERSONAL",true,T0,1,owner,"138****1234"));
    service.bindReferral(new PromotionStore.BindReferral("b1","first","p1",0,T0));
    service.bindReferral(new PromotionStore.BindReferral("b2","second","first",0,T0));
    var firstChange=rateChange("rate-1",1,null,"defaultLevel1CashRate","0.08","按页面配置","admin",T0.plusSeconds(10));
    assertEquals(2,service.changeBenefitRate(firstChange).version());
    assertEquals(2,service.changeBenefitRate(firstChange).version());
    assertThrows(IllegalStateException.class,()->service.changeBenefitRate(rateChange("conflict",1,null,"defaultLevel2CashRate","0.03","原因","admin",T0.plusSeconds(11))));
    service.changeBenefitRate(rateChange("rate-2",2,null,"defaultLevel2CashRate","0.03","按页面配置","admin",T0.plusSeconds(12)));
    service.changeBenefitRate(rateChange("override",3,"p1","level1CashRate","0.12","代理覆盖","admin",T0.plusSeconds(14)));
    service.projectPaidRecharge(new PromotionStore.PaidRecharge("old","first",new BigDecimal("100"),T0.plusSeconds(9),T0.plusSeconds(20),null));
    service.projectPaidRecharge(new PromotionStore.PaidRecharge("first-new","first",new BigDecimal("100"),T0.plusSeconds(12),T0.plusSeconds(20),null));
    service.projectPaidRecharge(new PromotionStore.PaidRecharge("second-new","second",new BigDecimal("100"),T0.plusSeconds(15),T0.plusSeconds(20),null));
    service.projectPaidRecharge(new PromotionStore.PaidRecharge("overridden","first",new BigDecimal("100"),T0.plusSeconds(15),T0.plusSeconds(20),null));
    assertEquals(new BigDecimal("30.0000"),service.getCommission("p1").frozen());
    service.changeBenefitRate(rateChange("inherit",4,"p1","level1CashRate",null,"恢复继承","admin",T0.plusSeconds(16)));
    assertEquals(null,service.benefitSettings().promoters().getFirst().level1CashRate());
    service.projectPaidRecharge(new PromotionStore.PaidRecharge("inherit","first",new BigDecimal("100"),T0.plusSeconds(17),T0.plusSeconds(20),null));
    service.projectPaidRecharge(new PromotionStore.PaidRecharge("old","first",new BigDecimal("100"),T0.plusSeconds(9),T0.plusSeconds(20),null));
    assertEquals(new BigDecimal("38.0000"),service.getCommission("p1").frozen());
    assertEquals(5,service.benefitSettingsHistory().size());
    assertThrows(IllegalArgumentException.class,()->service.changeBenefitRate(rateChange("bad",5,null,"defaultLevel1CashRate","0.1234567","原因","admin",T0.plusSeconds(18))));
  }

  @Test void zeroRateStillTracksPaidConsumptionRefundAndRetries() {
    approvePromoter("p1");
    service.bindReferral(new PromotionStore.BindReferral("bind-zero","customer","p1",0,T0));
    service.changeBenefitRate(rateChange("zero",1,null,"defaultLevel1CashRate","0","零比例","admin",T0.plusSeconds(1)));
    var paid=new PromotionStore.PaidRecharge("zero","customer",new BigDecimal("100"),T0.plusSeconds(2),T0.plusSeconds(3),null);
    service.projectPaidRecharge(paid); service.projectPaidRecharge(paid);
    var settle=new PromotionStore.SettleConsumption("zero-settle","zero-allocation","recharge:zero",new BigDecimal("20"),false,T0.plusSeconds(4));
    service.settleConsumption(settle); service.settleConsumption(settle);
    var refund=new PromotionStore.RefundRecharge("zero-refund","recharge:zero",new BigDecimal("100"),T0.plusSeconds(5));
    service.refundRecharge(refund); service.refundRecharge(refund);
    assertEquals(new BigDecimal("0.0000"),service.getCommission("p1").frozen());
    assertEquals(new BigDecimal("100.0000"),sql.queryForObject("SELECT refunded_paid_amount4 FROM promotion_funding_benefit WHERE order_id='zero'",BigDecimal.class));
    assertEquals(3,service.listCommissionEntries("p1",0,20).total());
  }

  @Test void boundedLocalCorrectionIsIdempotentAndFutureRefundsUseCorrectedAmounts() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(mysqlSchema != null, "local correction uses native MySQL");
    String promoter="6b4c3d7c-d2da-4f1c-aaaf-5ff421118bf5";
    String first="c2c6136f-d909-4329-ae4a-ad883273b575",second="ce16cf2d-cf1e-4c57-86ee-92d0c3b57303";
    String firstOrder="7c41fc17-5f4d-438f-b226-b7a12345d341",secondOrder="2ed6d85c-1331-4206-b21d-3b1e88f9ea04";
    approvePromoter(promoter);
    service.bindReferral(new PromotionStore.BindReferral("cf",first,promoter,0,T0));
    service.bindReferral(new PromotionStore.BindReferral("cs",second,first,0,T0));
    var paid=new PromotionStore.PaidRecharge(firstOrder,first,new BigDecimal("100"),T0.plusSeconds(1),T0.plusSeconds(2),null);
    service.projectPaidRecharge(paid);
    restoreLegacySecondLevelFunding(secondOrder, second, promoter);
    var historical = new PromotionStore.FreezeRecharge("paid-freeze:"+secondOrder,secondOrder,"recharge:"+secondOrder,second,1,1,new BigDecimal("100"),T0.plusSeconds(2));
    assertEquals(new BigDecimal("5.0000"),service.freezeRecharge(historical).changedAmount());
    assertEquals("RESOLVED",service.projectPaidRecharge(new PromotionStore.PaidRecharge(secondOrder,second,new BigDecimal("100"),T0.plusSeconds(1),T0.plusSeconds(2),null)));
    service.changeBenefitRate(rateChange("cl1",1,null,"defaultLevel1CashRate","0.08","用户截图","admin",T0.plusSeconds(3)));
    service.changeBenefitRate(rateChange("cl2",2,null,"defaultLevel2CashRate","0.03","用户截图","admin",T0.plusSeconds(4)));
    var root=java.nio.file.Path.of("").toAbsolutePath();
    while(!java.nio.file.Files.exists(root.resolve("services/promotion-service/promotion-infrastructure/src/test/resources/CorrectLocalRates.java")))root=root.getParent();
    String driver=Class.forName("com.mysql.cj.jdbc.Driver").getProtectionDomain().getCodeSource().getLocation().getPath();
    for(int n=0;n<2;n++) {
      var process=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","--class-path",driver,root.resolve("services/promotion-service/promotion-infrastructure/src/test/resources/CorrectLocalRates.java").toString());
      process.environment().put("BENEFIT_CORRECTION_JDBC_URL",System.getenv("PROMOTION_TEST_MYSQL_URL")+mysqlSchema);
      process.environment().put("BENEFIT_CORRECTION_USER",System.getenv("PROMOTION_TEST_MYSQL_USERNAME"));
      process.environment().put("BENEFIT_CORRECTION_PASSWORD",System.getenv("PROMOTION_TEST_MYSQL_PASSWORD"));
      var running=process.redirectErrorStream(true).start();String output=new String(running.getInputStream().readAllBytes());
      assertEquals(0,running.waitFor(),output);
    }
    assertEquals(new BigDecimal("11.0000"),service.getCommission(promoter).frozen());
    assertEquals(java.util.List.of(firstOrder),service.portalRecharges(promoter,0,20).items().stream().map(PromotionStore.PortalRecharge::orderId).toList());
    assertEquals(4,service.listCommissionEntries(promoter,0,20).total());
    assertEquals(2,sql.queryForObject("SELECT COUNT(*) FROM promotion_rate_correction",Integer.class));
    assertEquals("RESOLVED",service.projectPaidRecharge(paid));
    assertEquals(new BigDecimal("11.0000"),service.getCommission(promoter).frozen());
    service.refundRecharge(new PromotionStore.RefundRecharge("cf-refund","recharge:"+firstOrder,new BigDecimal("100"),T0.plusSeconds(5)));
    assertEquals(new BigDecimal("3.0000"),service.getCommission(promoter).frozen());
    service.settleConsumption(new PromotionStore.SettleConsumption("cs-consume","allocation-cs","recharge:"+secondOrder,new BigDecimal("100"),true,T0.plusSeconds(6)));
    assertEquals(new BigDecimal("3.0000"),service.getCommission(promoter).available());
    assertEquals(0,service.portalConsumptions(promoter,0,20).total());
    service.refundRecharge(new PromotionStore.RefundRecharge("cs-refund","recharge:"+secondOrder,new BigDecimal("100"),T0.plusSeconds(7)));
    assertEquals(new BigDecimal("0.0000"),service.getCommission(promoter).available());
    assertEquals(new BigDecimal("0.0000"),service.getCommission(promoter).cashDebt());
  }

  private void restoreLegacySecondLevelFunding(String order, String owner, String promoter) {
    // Database fixture of an already-frozen pre-change order; new requests must never create this tier.
    sql.update("UPDATE referral_binding SET level2_promoter_user_id=?,cash_promoter_user_id=?,cash_tier='LEVEL_2' WHERE referred_user_id=?", promoter,promoter,owner);
    long sequence=service.getCommission(promoter).version()+1;
    sql.update("INSERT INTO promotion_funding_benefit(funding_lot_id,source_event_id,order_id,wallet_owner_user_id,referral_binding_version,benefit_policy_version,promoter_user_id,cash_tier,cash_rate6,paid_amount4,frozen_commission4,occurred_at) VALUES(?,?,?,?,1,1,?,'LEVEL_2',0.05,100,5,?)",
        "recharge:"+order,"paid-freeze:"+order,order,owner,promoter,T0.plusSeconds(2).toString());
    sql.update("INSERT INTO promotion_commission_ledger(promoter_user_id,sequence_no,action_type,amount4,source_event_id,order_id,funding_lot_id,created_at) VALUES(?,?,'COMMISSION_FROZEN',5,?,?,?,?)",
        promoter,sequence,"paid-freeze:"+order,order,"recharge:"+order,T0.plusSeconds(2).toString());
    sql.update("UPDATE promotion_commission_account SET frozen4=frozen4+5,version=? WHERE promoter_user_id=?",sequence,promoter);
    String freezeFacts=java.util.stream.Stream.of(order,"recharge:"+order,owner,"1","1","100.0000",T0.plusSeconds(2).toString()).map(v->v.length()+":"+v).collect(java.util.stream.Collectors.joining());
    String paidFacts=java.util.stream.Stream.of(owner,"100.0000",T0.plusSeconds(1).toString(),T0.plusSeconds(2).toString()).map(v->v.length()+":"+v).collect(java.util.stream.Collectors.joining());
    sql.update("INSERT INTO promotion_command_receipt(command_id,command_type,aggregate_id,semantics,recorded_at) VALUES(?,'RECHARGE_FREEZE',?,?,?)", "paid-freeze:"+order,"recharge:"+order,freezeFacts,T0.plusSeconds(2).toString());
    sql.update("INSERT INTO promotion_command_receipt(command_id,command_type,aggregate_id,semantics,recorded_at) VALUES(?,'PAID_RECHARGE',?,?,?)", "paid-credit:"+order,order,paidFacts,T0.plusSeconds(2).toString());
  }

  @Test void legacyIndirectInvitesCannotFreezeNewCashAndAreExcludedFromInvitedUsers() {
    approvePromoter("p1");
    service.bindReferral(new PromotionStore.BindReferral("b1","first","p1",0,T0));
    service.bindReferral(new PromotionStore.BindReferral("b2","second","first",0,T0));
    sql.update("UPDATE referral_binding SET level2_promoter_user_id='p1',cash_promoter_user_id='p1',cash_tier='LEVEL_2' WHERE referred_user_id='second'");
    var recharge=new PromotionStore.PaidRecharge("new-indirect","second",new BigDecimal("100"),T0.plusSeconds(1),T0.plusSeconds(2),null);
    assertEquals("NONE",service.projectPaidRecharge(recharge));
    assertEquals("NONE",service.projectPaidRecharge(recharge));
    assertThrows(IllegalStateException.class,()->service.freezeRecharge(new PromotionStore.FreezeRecharge("new-freeze","new-order","new-lot","second",1,1,new BigDecimal("100"),T0.plusSeconds(3))));
    assertEquals(new BigDecimal("0.0000"),service.getCommission("p1").frozen());
    assertEquals(1,service.portalOverview("p1").downlineCount());
    assertEquals(0,service.portalOverview("p1").levelTwoCount());
    assertEquals(1,service.portalDownlines("p1",0,20).total());
  }

  private PromotionStore.ChangeBenefitRate rateChange(String command,long version,String promoter,String field,String rate,String reason,String admin,Instant time) {
    store.setBenefitClock(java.time.Clock.fixed(time,java.time.ZoneOffset.UTC));
    return new PromotionStore.ChangeBenefitRate(command,version,promoter,field,rate,reason,admin);
  }

  @Test void policyEffectiveTimeIsSampledAfterWaitingForSaveLock() throws Exception {
    approvePromoter("p1");
    service.bindReferral(new PromotionStore.BindReferral("lock-bind","customer","p1",0,T0));
    var now=new java.util.concurrent.atomic.AtomicReference<>(T0.plusSeconds(10));
    store.setBenefitClock(new java.time.Clock() {
      public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
      public java.time.Clock withZone(java.time.ZoneId zone){return this;}
      public Instant instant(){return now.get();}
    });
    try(var connection=sql.getDataSource().getConnection();var pool=Executors.newSingleThreadExecutor()) {
      connection.setAutoCommit(false);
      try(var statement=connection.createStatement()){statement.executeQuery("SELECT version FROM promotion_benefit_policy WHERE version=1 FOR UPDATE").close();}
      var pending=pool.submit(()->service.changeBenefitRate(new PromotionStore.ChangeBenefitRate("wait-save",1,null,"defaultLevel1CashRate","0.08","保存","admin")));
      assertThrows(java.util.concurrent.TimeoutException.class,()->pending.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));
      now.set(T0.plusSeconds(30));connection.commit();
      assertEquals(T0.plusSeconds(30),Instant.parse(pending.get().effectiveAt()));
    }
    service.projectPaidRecharge(new PromotionStore.PaidRecharge("during-wait","customer",new BigDecimal("100"),T0.plusSeconds(20),T0.plusSeconds(40),null));
    assertEquals(new BigDecimal("10.0000"),service.getCommission("p1").frozen());
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
    var mapper = session.getMapper(PromotionSqlMapper.class);
    var invitationMapper = (PromotionSqlMapper) java.lang.reflect.Proxy.newProxyInstance(
        PromotionSqlMapper.class.getClassLoader(), new Class<?>[]{PromotionSqlMapper.class}, (proxy, method, args) -> {
          if (method.getName().equals("ensureInvitation") && args.length == 3) beforeInvitationInsert.accept(((Number)args[2]).longValue());
          try { return method.invoke(mapper,args); }
          catch(java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
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
        invitationMapper, new DataSourceTransactionManager(dataSource));
    service = new PromotionService(store);
  }

  @AfterEach
  void dropTestSchema() {
    if (mysqlRoot != null && mysqlSchema != null) {
      mysqlRoot.execute("DROP DATABASE " + mysqlSchema);
    }
  }

  @Test
  void administratorOpensRealUserWithoutApplicationAndRetainsZeroAccountOnReplay() {
    store.setAdminUserDirectory(user -> user.equals("agent-1"));
    var created = service.createAdminPromoter(adminCreate("create-1", "agent-1", T0));
    assertEquals(PromoterStatus.ENABLED, created.status());
    assertEquals(T0, created.createdAt());
    org.junit.jupiter.api.Assertions.assertTrue(created.invitationCode().matches("[A-Z0-9]{4}"));
    var account = service.getCommission("agent-1");
    for (BigDecimal amount : java.util.List.of(account.frozen(), account.available(), account.withdrawing(),
        account.withdrawn(), account.cashDebt(), account.changedAmount())) assertEquals(0, amount.signum());
    assertEquals(0, account.version());
    assertEquals(created, service.createAdminPromoter(adminCreate("create-1", "agent-1", T0.plusSeconds(1))));
    assertEquals(created, service.createAdminPromoter(adminCreate("create-2", "agent-1", T0.plusSeconds(2))));
    assertEquals(account, service.getCommission("agent-1"));
    assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM promoter_account", Integer.class));
    assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_account", Integer.class));
    assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM promotion_application", Integer.class));
    assertEquals(2, sql.queryForObject("SELECT COUNT(*) FROM promotion_command_receipt WHERE command_type='ADMIN_PROMOTER_CREATE'", Integer.class));
    String semantics = sql.queryForObject("SELECT semantics FROM promotion_command_receipt WHERE command_id='create-1'", String.class);
    org.junit.jupiter.api.Assertions.assertTrue(semantics.contains("admin-1"));
    assertThrows(IllegalStateException.class, () -> service.createAdminPromoter(adminCreate("create-1", "other", T0)));
    assertThrows(IllegalStateException.class, () -> service.createAdminPromoter(
        new PromotionStore.AdminCreatePromoter("create-1", "agent-1", "other-admin", T0)));
    service.setPromoterStatus(new PromotionStore.ChangePromoterStatus("disable-1", "agent-1", PromoterStatus.DISABLED, "admin-1", T0.plusSeconds(3)));
    assertThrows(IllegalStateException.class, () -> service.createAdminPromoter(adminCreate("create-3", "agent-1", T0.plusSeconds(4))));
    assertThrows(IllegalStateException.class, () -> service.createAdminPromoter(adminCreate("create-1", "agent-1", T0.plusSeconds(4))));
    assertEquals(PromoterStatus.DISABLED, service.getPromoter("agent-1").status());
  }

  @Test
  void administratorCreationFailsClosedWithoutValidUserDirectoryAndValidCommand() {
    assertThrows(IllegalStateException.class, () -> service.createAdminPromoter(adminCreate("create", "missing", T0)));
    store.setAdminUserDirectory(user -> false);
    assertThrows(IllegalStateException.class, () -> service.createAdminPromoter(adminCreate("create", "disabled", T0)));
    store.setAdminUserDirectory(user -> { throw new IllegalArgumentException("user not found"); });
    assertThrows(IllegalArgumentException.class, () -> service.createAdminPromoter(adminCreate("create", "missing", T0)));
    store.setAdminUserDirectory(user -> { throw new IllegalStateException("directory unavailable"); });
    assertThrows(IllegalStateException.class, () -> service.createAdminPromoter(adminCreate("create", "unavailable", T0)));
    store.setAdminUserDirectory(user -> { throw new AssertionError("invalid commands must not query user directory"); });
    for (String id : new String[] {null, "", " ", "x".repeat(129)}) {
      assertThrows(IllegalArgumentException.class, () -> service.createAdminPromoter(new PromotionStore.AdminCreatePromoter(id, "agent", "admin", T0)));
      assertThrows(IllegalArgumentException.class, () -> service.createAdminPromoter(new PromotionStore.AdminCreatePromoter("create", id, "admin", T0)));
      assertThrows(IllegalArgumentException.class, () -> service.createAdminPromoter(new PromotionStore.AdminCreatePromoter("create", "agent", id, T0)));
    }
    assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM promoter_account", Integer.class));
    assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_account", Integer.class));
    assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM promotion_command_receipt", Integer.class));
  }

  @Test
  void administratorListsAllStatusesWithStablePaginationAndOptionalUser() {
    store.setAdminUserDirectory(user -> true);
    service.createAdminPromoter(adminCreate("create-b", "agent-b", T0));
    service.createAdminPromoter(adminCreate("create-a", "agent-a", T0));
    service.createAdminPromoter(adminCreate("create-c", "agent-c", T0.plusSeconds(1)));
    service.setPromoterStatus(new PromotionStore.ChangePromoterStatus("disable-a", "agent-a", PromoterStatus.DISABLED, "admin-1", T0.plusSeconds(2)));
    assertEquals(3, service.listPromoters(null, 0, 1).total());
    assertEquals("agent-c", service.listPromoters(null, 0, 1).items().getFirst().userId());
    assertEquals("agent-a", service.listPromoters(null, 1, 1).items().getFirst().userId());
    assertEquals(PromoterStatus.DISABLED, service.listPromoters(null, 1, 1).items().getFirst().status());
    assertEquals("agent-b", service.listPromoters(null, 2, 1).items().getFirst().userId());
    assertEquals(0, service.listPromoters(null, 3, 1).items().size());
    assertEquals(1, service.listPromoters("agent-a", 0, 20).total());
    assertEquals(0, service.listPromoters("missing", 0, 20).total());
    assertEquals(3, service.listPromoters("", 0, 20).total());
    assertThrows(IllegalArgumentException.class, () -> service.listPromoters(null, -1, 20));
    assertThrows(IllegalArgumentException.class, () -> service.listPromoters(null, 0, 101));
    assertThrows(IllegalArgumentException.class, () -> service.listPromoters(null, 0, 0));
  }

  @Test
  void concurrentAdministratorCreationProducesOnePromoterAndAccount() throws Exception {
    for (boolean sameCommand : new boolean[] {true, false}) {
      String user = "agent-" + sameCommand;
      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      store.setAdminUserDirectory(id -> { ready.countDown(); try { start.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        return true; });
      try (var pool = Executors.newFixedThreadPool(2)) {
        var first = pool.submit(() -> service.createAdminPromoter(adminCreate(user + "-1", user, T0)));
        var second = pool.submit(() -> service.createAdminPromoter(adminCreate(user + (sameCommand ? "-1" : "-2"), user, T0.plusSeconds(1))));
        org.junit.jupiter.api.Assertions.assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        assertEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
      } finally { start.countDown(); }
      assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM promoter_account WHERE promoter_user_id=?", Integer.class, user));
      assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_account WHERE promoter_user_id=?", Integer.class, user));
    }
  }

  private static PromotionStore.AdminCreatePromoter adminCreate(String commandId, String userId, Instant at) {
    return new PromotionStore.AdminCreatePromoter(commandId, userId, "admin-1", at);
  }

  @Test
  void applicationApprovalCreatesPromoterAndStatusCanBeManagedIdempotently() {
    projectEligibility("promoter-1", "ENABLED");
    var command = new PromotionStore.SubmitApplication(
        "submit-1", "application-1", "promoter-1", "share product", "community", true, T0);
    assertEquals("PENDING", service.submitApplication(command).status());
    var replayed = service.submitApplication(command);
    assertEquals("PENDING", replayed.status());
    assertEquals(1, replayed.eligibilitySourceVersion());
    assertEquals(T0.minusSeconds(30), replayed.eligibilitySnapshotAt());
    assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM promotion_application", Integer.class));
    assertThrows(IllegalStateException.class, () -> service.submitApplication(
        new PromotionStore.SubmitApplication("submit-1", "application-2", "promoter-1",
            "changed", "community", true, T0)));

    var approved = service.reviewApplication(new PromotionStore.ReviewApplication(
        "review-1", "application-1", "admin-1", ApplicationDecision.APPROVE, null, T0.plusSeconds(1)));
    assertEquals("APPROVED", approved.status());
    assertEquals("APPROVED", service.reviewApplication(new PromotionStore.ReviewApplication(
        "review-1", "application-1", "admin-1", ApplicationDecision.APPROVE, null,
        T0.plusSeconds(1))).status());
    assertEquals(PromoterStatus.ENABLED, service.getPromoter("promoter-1").status());

    assertEquals(PromoterStatus.DISABLED, service.setPromoterStatus(
        new PromotionStore.ChangePromoterStatus("status-1", "promoter-1", PromoterStatus.DISABLED,
            "admin-1", T0.plusSeconds(2))).status());

    projectEligibility("promoter-2", "ENABLED");
    service.submitApplication(new PromotionStore.SubmitApplication(
        "submit-2", "application-2", "promoter-2", "share", "community", true, T0));
    var rejected = service.reviewApplication(new PromotionStore.ReviewApplication(
        "review-2", "application-2", "admin-1", ApplicationDecision.REJECT,
        "requirements not met", T0.plusSeconds(1)));
    assertEquals("REJECTED", rejected.status());
    assertEquals("requirements not met", rejected.rejectionReason());
    assertThrows(IllegalArgumentException.class, () -> service.getPromoter("promoter-2"));
    assertEquals(2, service.listApplications(null, null, 0, 1).total());
    assertEquals("application-2", service.listApplications(null, null, 1, 1).items().getFirst().id());
    assertEquals("REJECTED", service.listApplications("REJECTED", "promoter-2", 0, 1)
        .items().getFirst().status());
    assertEquals(0, service.listApplications(null, null, 2, 1).items().size());
  }

  @Test
  void applicationRequiresTrustedCurrentEligibility() {
    assertThrows(IllegalStateException.class, () -> service.submitApplication(
        new PromotionStore.SubmitApplication("submit-1", "application-1", "user-1",
            "share", "community", true, T0)));

    projectEligibility("user-1", "DISABLED");
    assertThrows(IllegalStateException.class, () -> service.submitApplication(
        new PromotionStore.SubmitApplication("submit-1", "application-1", "user-1",
            "share", "community", true, T0)));

    service.projectApplicantEligibility(new PromotionStore.ProjectApplicantEligibility(
        "eligibility-user-1-v2", "user-1", "PERSONAL", "ENABLED", T0.minusSeconds(60),
        2, T0.minusSeconds(10)));
    assertEquals("ENABLED", service.submitApplication(new PromotionStore.SubmitApplication(
        "submit-1", "application-1", "user-1", "share", "community", true, T0))
        .accountStatus());
  }

  @Test
  void referralBindingResolvesOnlyDirectAgentCashAndOrdinaryInviterPoints() {
    approvePromoter("p1");
    var first = service.bindReferral(new PromotionStore.BindReferral(
        "bind-1", "middle-user", "p1", 0, T0.plusSeconds(2)));
    assertEquals("p1", first.level1PromoterUserId());
    assertEquals(null, first.level2PromoterUserId());

    var second = service.bindReferral(new PromotionStore.BindReferral(
        "bind-2", "customer", "middle-user", 0, T0.plusSeconds(3)));
    assertEquals(null, second.level1PromoterUserId());
    assertEquals(null, second.level2PromoterUserId());
    assertEquals("middle-user", second.pointBeneficiaryUserId());
    assertEquals(second, service.bindReferral(new PromotionStore.BindReferral(
        "bind-2", "customer", "middle-user", 0, T0.plusSeconds(3))));

    var third = service.bindReferral(new PromotionStore.BindReferral(
        "bind-4", "deep-user", "customer", 0, T0.plusSeconds(4)));
    assertEquals(null, third.level1PromoterUserId());
    assertEquals(null, third.level2PromoterUserId());
    assertEquals("customer", third.pointBeneficiaryUserId());

    var rebound = service.bindReferral(new PromotionStore.BindReferral(
        "bind-3", "customer", "p1", 1, T0.plusSeconds(5)));
    assertEquals(2, rebound.version());
    var history = service.listReferralHistory("customer", 0, 20);
    assertEquals(2, history.total());
    assertEquals(2, history.items().getFirst().version());
    assertEquals(1, history.items().get(1).version());
    assertEquals(1, service.listReferralHistory("customer", 1, 1).items().getFirst().version());
    assertEquals(2, service.listReferralHistory("customer", 2, 1).total());
    assertEquals(0, service.listReferralHistory("customer", 2, 1).items().size());
  }

  @Test
  void commissionAmountsAreExactAndIdempotentAndFailedReplayRollsBack() {
    approvePromoter("p1");
    service.bindReferral(new PromotionStore.BindReferral(
        "bind-1", "customer", "p1", 0, T0.plusSeconds(2)));
    var freeze = new PromotionStore.FreezeRecharge(
        "recharge-1", "order-1", "lot-1", "customer", 1, 1, new BigDecimal("100.0059"),
        T0.plusSeconds(3));
    var frozen = service.freezeRecharge(freeze);
    assertEquals(new BigDecimal("10.0005"), frozen.changedAmount());
    assertEquals(new BigDecimal("10.0005"), frozen.frozen());
    assertEquals(frozen, service.freezeRecharge(freeze));
    assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger", Integer.class));

    assertThrows(IllegalStateException.class, () -> service.freezeRecharge(
        new PromotionStore.FreezeRecharge("recharge-1", "order-1", "lot-1", "customer",
            1, 1, new BigDecimal("200"), T0.plusSeconds(3))));
    assertEquals(new BigDecimal("10.0005"), service.getCommission("p1").frozen());
    assertThrows(IllegalArgumentException.class, () -> service.freezeRecharge(
        new PromotionStore.FreezeRecharge("tiny", "tiny-order", "tiny-lot", "customer",
            1, 1, new BigDecimal("0.00001"), T0.plusSeconds(3))));

    var released = service.settleConsumption(new PromotionStore.SettleConsumption(
        "consume-1", "allocation-1", "lot-1", new BigDecimal("50.0029"), false,
        T0.plusSeconds(4)));
    assertEquals(new BigDecimal("5.0002"), released.changedAmount());
    assertEquals(new BigDecimal("5.0003"), released.frozen());
    assertEquals(new BigDecimal("5.0002"), released.available());
    assertEquals(released, service.settleConsumption(new PromotionStore.SettleConsumption(
        "consume-1", "allocation-1", "lot-1", new BigDecimal("50.0029"), false,
        T0.plusSeconds(4))));
    assertThrows(IllegalStateException.class, () -> service.settleConsumption(
        new PromotionStore.SettleConsumption("consume-1", "allocation-1", "lot-1",
            new BigDecimal("10"), false, T0.plusSeconds(4))));
    assertThrows(IllegalStateException.class, () -> service.settleConsumption(
        new PromotionStore.SettleConsumption("consume-2", "allocation-1", "lot-1",
            new BigDecimal("1"), false, T0.plusSeconds(5))));

    var refunded = service.refundRecharge(new PromotionStore.RefundRecharge(
        "refund-1", "lot-1", new BigDecimal("10"), T0.plusSeconds(6)));
    assertEquals(new BigDecimal("0.9999"), refunded.changedAmount());
    assertEquals(refunded, service.refundRecharge(new PromotionStore.RefundRecharge(
        "refund-1", "lot-1", new BigDecimal("10"), T0.plusSeconds(6))));
    assertThrows(IllegalStateException.class, () -> service.refundRecharge(
        new PromotionStore.RefundRecharge("refund-1", "lot-1", new BigDecimal("11"),
            T0.plusSeconds(6))));
    assertEquals(3, sql.queryForObject("SELECT COUNT(*) FROM promotion_commission_ledger", Integer.class));
    assertEquals(new BigDecimal("4.0004"), service.getCommission("p1").frozen());
  }

  @Test
  void delayedRechargeUsesTheFrozenBindingAndPolicyVersions() {
    approvePromoter("p1");
    approvePromoter("p2");
    service.bindReferral(new PromotionStore.BindReferral(
        "bind-1", "customer", "p1", 0, T0.plusSeconds(2)));
    service.bindReferral(new PromotionStore.BindReferral(
        "bind-2", "customer", "p2", 1, T0.plusSeconds(3)));

    var frozen = service.freezeRecharge(new PromotionStore.FreezeRecharge(
        "recharge-1", "order-1", "lot-1", "customer", 1, 1,
        new BigDecimal("100"), T0.plusSeconds(2)));
    assertEquals("p1", frozen.promoterUserId());
    assertEquals(new BigDecimal("10.0000"), frozen.changedAmount());
    assertEquals(new BigDecimal("0.0000"), service.getCommission("p2").frozen());
  }

  @Test
  void withdrawalMovesReservedMoneyOnlyThroughAllowedStates() {
    approvePromoter("p1");
    service.bindReferral(new PromotionStore.BindReferral(
        "bind-1", "customer", "p1", 0, T0.plusSeconds(2)));
    service.freezeRecharge(new PromotionStore.FreezeRecharge(
        "recharge-1", "order-1", "lot-1", "customer", 1, 1, new BigDecimal("1000"),
        T0.plusSeconds(3)));
    service.settleConsumption(new PromotionStore.SettleConsumption(
        "consume-1", "allocation-1", "lot-1", new BigDecimal("1000"), true,
        T0.plusSeconds(4)));
    projectPayoutAccount("payout-p1", "p1", PayoutAccountStatus.ACTIVE);

    sql.update("UPDATE promotion_payout_account SET bank_name=?, qr_code_reference=? WHERE payout_account_id=?", "Original Bank", "original-qr", "payout-p1");
    var request = new PromotionStore.RequestWithdrawal(
        "withdraw-command-1", "withdrawal-1", "p1", new BigDecimal("100"), "payout-p1",
        T0.plusSeconds(5));
    var requested = service.requestWithdrawal(request);
    assertEquals(WithdrawalStatus.WITHDRAWING, requested.status());
    sql.update("UPDATE promotion_payout_account SET bank_name=?, qr_code_reference=? WHERE payout_account_id=?", "Changed Bank", "changed-qr", "payout-p1");
    assertEquals("Original Bank", sql.queryForObject("SELECT payout_bank_name FROM promotion_withdrawal WHERE withdrawal_id='withdrawal-1'", String.class));
    assertEquals("original-qr", sql.queryForObject("SELECT payout_qr_code_reference FROM promotion_withdrawal WHERE withdrawal_id='withdrawal-1'", String.class));
    assertEquals("payout-p1", requested.payoutAccountId());
    assertEquals(1, requested.payoutAccountSourceVersion());
    assertEquals(T0.plusSeconds(4), requested.payoutAccountSnapshotAt());
    assertEquals(new BigDecimal("100.0000"), service.getCommission("p1").withdrawing());
    assertEquals(WithdrawalStatus.WITHDRAWING, service.requestWithdrawal(request).status());
    assertEquals(requested, service.requestWithdrawal(new PromotionStore.RequestWithdrawal(
        request.commandId(), request.withdrawalId(), request.promoterUserId(), request.amount(),
        request.payoutAccountId(), T0.plusSeconds(50))));
    assertThrows(IllegalStateException.class, () -> service.requestWithdrawal(
        new PromotionStore.RequestWithdrawal("withdraw-command-1", "withdrawal-1", "p1",
            new BigDecimal("101"), "payout-p1", T0.plusSeconds(5))));
    assertEquals(new BigDecimal("100.0000"), service.getCommission("p1").withdrawing());
    // Pre-portal receipts included the first server timestamp. Preserve their retry semantics too.
    String timestamp = requested.requestedAt().toString();
    sql.update("UPDATE promotion_command_receipt SET semantics = CONCAT(semantics, ?) WHERE command_id = ?",
        timestamp.length() + ":" + timestamp, request.commandId());
    assertEquals(requested, service.requestWithdrawal(new PromotionStore.RequestWithdrawal(
        request.commandId(), request.withdrawalId(), request.promoterUserId(), request.amount(),
        request.payoutAccountId(), T0.plusSeconds(50))));

    assertEquals(WithdrawalStatus.PENDING_PAYOUT, service.decideWithdrawal(
        new PromotionStore.DecideWithdrawal("approve-1", "withdrawal-1", "admin-1", true, null,
            T0.plusSeconds(6))).status());
    assertEquals(WithdrawalStatus.PAID, service.completeWithdrawal(
        new PromotionStore.CompleteWithdrawal("paid-1", "withdrawal-1", "admin-1",
            T0.plusSeconds(7))).status());
    assertEquals(new BigDecimal("100.0000"), service.getCommission("p1").withdrawn());
    assertThrows(IllegalStateException.class, () -> service.decideWithdrawal(
        new PromotionStore.DecideWithdrawal("reject-late", "withdrawal-1", "admin-1", false,
            "late", T0.plusSeconds(8))));
  }

  @Test
  void rejectedWithdrawalReturnsTheReservedBalanceExactlyOnce() {
    approvePromoter("p1");
    service.bindReferral(new PromotionStore.BindReferral(
        "bind-1", "customer", "p1", 0, T0.plusSeconds(2)));
    service.freezeRecharge(new PromotionStore.FreezeRecharge(
        "recharge-1", "order-1", "lot-1", "customer", 1, 1, new BigDecimal("1000"),
        T0.plusSeconds(3)));
    service.settleConsumption(new PromotionStore.SettleConsumption(
        "consume-1", "allocation-1", "lot-1", new BigDecimal("1000"), true,
        T0.plusSeconds(4)));
    projectPayoutAccount("payout-p1", "p1", PayoutAccountStatus.ACTIVE);
    service.requestWithdrawal(new PromotionStore.RequestWithdrawal(
        "withdraw-command-1", "withdrawal-1", "p1", new BigDecimal("100"), "payout-p1",
        T0.plusSeconds(5)));

    var decision = new PromotionStore.DecideWithdrawal(
        "reject-1", "withdrawal-1", "admin-1", false, "account mismatch",
        T0.plusSeconds(6));
    assertEquals(WithdrawalStatus.REJECTED, service.decideWithdrawal(decision).status());
    assertEquals(WithdrawalStatus.REJECTED, service.decideWithdrawal(decision).status());
    assertThrows(IllegalStateException.class, () -> service.decideWithdrawal(
        new PromotionStore.DecideWithdrawal("reject-1", "withdrawal-1", "admin-1", false,
            "different reason", T0.plusSeconds(6))));
    assertEquals(new BigDecimal("100.0000"), service.getCommission("p1").available());
    assertEquals(new BigDecimal("0.0000"), service.getCommission("p1").withdrawing());
    assertThrows(IllegalStateException.class, () -> service.completeWithdrawal(
        new PromotionStore.CompleteWithdrawal("paid-late", "withdrawal-1", "admin-1",
            T0.plusSeconds(7))));
  }

  @Test
  void refundAfterApprovalCanCompensateTheReservedWithdrawal() {
    approvePromoter("p1");
    service.bindReferral(new PromotionStore.BindReferral(
        "bind-1", "customer", "p1", 0, T0.plusSeconds(2)));
    service.freezeRecharge(new PromotionStore.FreezeRecharge(
        "recharge-1", "order-1", "lot-1", "customer", 1, 1,
        new BigDecimal("1000"), T0.plusSeconds(3)));
    service.settleConsumption(new PromotionStore.SettleConsumption(
        "consume-1", "allocation-1", "lot-1", new BigDecimal("1000"), true,
        T0.plusSeconds(4)));
    projectPayoutAccount("payout-p1", "p1", PayoutAccountStatus.ACTIVE);
    service.requestWithdrawal(new PromotionStore.RequestWithdrawal(
        "withdraw-command-1", "withdrawal-1", "p1", new BigDecimal("100"), "payout-p1",
        T0.plusSeconds(5)));
    assertEquals(WithdrawalStatus.PENDING_PAYOUT, service.decideWithdrawal(
        new PromotionStore.DecideWithdrawal("approve-1", "withdrawal-1", "admin-1", true,
            null, T0.plusSeconds(6))).status());

    var reversed = service.refundRecharge(new PromotionStore.RefundRecharge(
        "refund-1", "lot-1", new BigDecimal("1000"), T0.plusSeconds(7)));
    assertEquals(new BigDecimal("100.0000"), reversed.changedAmount());
    assertEquals(new BigDecimal("100.0000"), reversed.cashDebt());
    assertEquals(new BigDecimal("100.0000"), reversed.withdrawing());
    assertThrows(IllegalStateException.class, () -> service.completeWithdrawal(
        new PromotionStore.CompleteWithdrawal("pay-1", "withdrawal-1", "admin-1",
            T0.plusSeconds(8))));

    assertEquals(WithdrawalStatus.REJECTED, service.decideWithdrawal(
        new PromotionStore.DecideWithdrawal("reject-1", "withdrawal-1", "admin-1", false,
            "original recharge refunded", T0.plusSeconds(9))).status());
    var account = service.getCommission("p1");
    assertEquals(new BigDecimal("0.0000"), account.available());
    assertEquals(new BigDecimal("0.0000"), account.withdrawing());
    assertEquals(new BigDecimal("0.0000"), account.cashDebt());
  }

  @Test
  void withdrawalRequiresAnActivePayoutAccountOwnedByThePromoter() {
    approvePromoter("p1");
    service.bindReferral(new PromotionStore.BindReferral(
        "bind-1", "customer", "p1", 0, T0.plusSeconds(2)));
    service.freezeRecharge(new PromotionStore.FreezeRecharge(
        "recharge-1", "order-1", "lot-1", "customer", 1, 1,
        new BigDecimal("1000"), T0.plusSeconds(3)));
    service.settleConsumption(new PromotionStore.SettleConsumption(
        "consume-1", "allocation-1", "lot-1", new BigDecimal("1000"), true,
        T0.plusSeconds(4)));

    projectPayoutAccount("other-account", "p2", PayoutAccountStatus.ACTIVE);
    projectPayoutAccount("disabled-account", "p1", PayoutAccountStatus.DISABLED);
    assertThrows(IllegalStateException.class, () -> service.requestWithdrawal(
        new PromotionStore.RequestWithdrawal("withdraw-1", "withdrawal-1", "p1",
            new BigDecimal("100"), "other-account", T0.plusSeconds(5))));
    assertThrows(IllegalStateException.class, () -> service.requestWithdrawal(
        new PromotionStore.RequestWithdrawal("withdraw-2", "withdrawal-2", "p1",
            new BigDecimal("100"), "disabled-account", T0.plusSeconds(5))));

    projectPayoutAccount("active-account", "p1", PayoutAccountStatus.ACTIVE);
    assertEquals("****5678", service.requestWithdrawal(new PromotionStore.RequestWithdrawal(
        "withdraw-3", "withdrawal-3", "p1", new BigDecimal("100"), "active-account",
        T0.plusSeconds(5))).maskedPayoutAccountNo());
  }

  @Test
  void concurrentReplayProducesOneCommissionMutation() throws Exception {
    approvePromoter("p1");
    service.bindReferral(new PromotionStore.BindReferral(
        "bind-1", "customer", "p1", 0, T0.plusSeconds(2)));
    var command = new PromotionStore.FreezeRecharge(
        "recharge-1", "order-1", "lot-1", "customer", 1, 1,
        new BigDecimal("100"), T0.plusSeconds(3));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    Callable<PromotionStore.CommissionView> request = () -> {
      ready.countDown();
      start.await();
      return service.freezeRecharge(command);
    };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(request);
      var second = pool.submit(request);
      ready.await();
      start.countDown();
      assertEquals(first.get().changedAmount(), second.get().changedAmount());
    }
    assertEquals(1, sql.queryForObject(
        "SELECT COUNT(*) FROM promotion_commission_ledger", Integer.class));
  }

  @Test
  void concurrentApplicationReviewReturnsTheCommittedReplay() throws Exception {
    projectEligibility("p1", "ENABLED");
    service.submitApplication(new PromotionStore.SubmitApplication(
        "submit-p1", "application-p1", "p1", "share", "community", true, T0));
    var command = new PromotionStore.ReviewApplication(
        "review-p1", "application-p1", "admin-1", ApplicationDecision.APPROVE, null,
        T0.plusSeconds(1));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    Callable<PromotionStore.ApplicationView> request = () -> {
      ready.countDown();
      start.await();
      return service.reviewApplication(command);
    };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(request);
      var second = pool.submit(request);
      ready.await();
      start.countDown();
      assertEquals(first.get(), second.get());
    }
    assertEquals(1, sql.queryForObject(
        "SELECT COUNT(*) FROM promoter_account WHERE promoter_user_id = 'p1'", Integer.class));
  }

  @Test
  void concurrentFinalSettlementReturnsTheCommittedReplay() throws Exception {
    approvePromoter("p1");
    service.bindReferral(new PromotionStore.BindReferral(
        "bind-1", "customer", "p1", 0, T0.plusSeconds(2)));
    service.freezeRecharge(new PromotionStore.FreezeRecharge(
        "recharge-1", "order-1", "lot-1", "customer", 1, 1,
        new BigDecimal("100"), T0.plusSeconds(3)));
    var command = new PromotionStore.SettleConsumption(
        "consume-1", "allocation-1", "lot-1", new BigDecimal("100"), true,
        T0.plusSeconds(4));
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    Callable<PromotionStore.CommissionView> request = () -> {
      ready.countDown();
      start.await();
      return service.settleConsumption(command);
    };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(request);
      var second = pool.submit(request);
      ready.await();
      start.countDown();
      assertEquals(first.get().available(), second.get().available());
    }
    assertEquals(1, sql.queryForObject(
        "SELECT COUNT(*) FROM promotion_commission_ledger WHERE allocation_id = 'allocation-1'",
        Integer.class));
  }


  @Test
  void failedApprovalRollsBackTheApplicationChangeAndReceipt() {
    projectEligibility("p1", "ENABLED");
    service.submitApplication(new PromotionStore.SubmitApplication(
        "second-submit", "second-application", "p1", "share again", "community", true, T0));
    sql.update("INSERT INTO promoter_account(promoter_user_id,invitation_code,status,created_at,updated_at) VALUES(?,?,?,?,?)", "p1","EXISTING-P1","ENABLED",T0.toString(),T0.toString());
    assertThrows(IllegalStateException.class, () -> service.reviewApplication(
        new PromotionStore.ReviewApplication("second-review", "second-application", "admin",
            ApplicationDecision.APPROVE, null, T0.plusSeconds(1))));
    assertEquals("PENDING", service.getApplication("second-application").status());
    assertEquals(0, sql.queryForObject(
        "SELECT COUNT(*) FROM promotion_command_receipt WHERE command_id = 'second-review'", Integer.class));
    assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM promoter_account", Integer.class));
  }

  @Test
  void portalReadsCurrentRelationshipsAndSavesOnlyOwnerValidatedPayouts() {
    approvePromoter("p1"); approvePromoter("p2");
    service.bindReferral(new PromotionStore.BindReferral("b1", "customer", "p1", 0, T0.plusSeconds(2)));
    service.freezeRecharge(new PromotionStore.FreezeRecharge("r1", "o1", "lot1", "customer", 1, 1,
        new BigDecimal("1234.5678"), T0.plusSeconds(3)));
    assertEquals(1, service.portalDownlines("p1", 0, 20).total());
    assertEquals(0, service.portalDownlines("p2", 0, 20).total());
    assertEquals("1234.5678", service.portalOverview("p1").paidAmount4());
    assertEquals("****omer", service.portalRecharges("p1", 0, 20).items().getFirst().maskedUserId());
    service.settleConsumption(new PromotionStore.SettleConsumption("settle1", "allocation1", "lot1", new BigDecimal("12.3456"), false, T0.plusSeconds(4)));
    assertEquals("12.3456", service.portalConsumptions("p1", 0, 20).items().getFirst().paidAmount4());
    store.setPortalDirectory(owner -> new PromotionStore.PortalDownlineFacts("Real Customer", "138****5678", true, "100.0000", "10.0000", T0.minusSeconds(100)));
    assertEquals(T0.minusSeconds(100), service.portalDownlines("p1", 0, 20).items().getFirst().registeredAt());
    store.setPortalAllocationDirectory(lookup -> {
      assertEquals("customer", lookup.ownerId()); assertEquals("lot1", lookup.fundingLotId()); assertEquals("allocation1", lookup.allocationId());
      return new PromotionStore.PortalAllocationFacts("IMAGE", "123.4560", "1000.0000", "876.5440", "100.0000", "87.6544");
    });
    assertEquals("Real Customer", service.portalRecharges("p1", 0, 20).items().getFirst().nickname());
    var consumed = service.portalConsumptions("p1", 0, 20).items().getFirst();
    assertEquals("138****5678", consumed.maskedPhone()); assertEquals("IMAGE", consumed.type());
    assertEquals("1000.0000", consumed.balanceBeforePoints4()); assertEquals("876.5440", consumed.balanceAfterPoints4());
    assertEquals("100.0000", consumed.balanceBeforeAmount4()); assertEquals("87.6544", consumed.balanceAfterAmount4());
    assertEquals(0, service.portalConsumptions("p2", 0, 20).total());
    var save = new PromotionStore.SavePayoutAccount("save1", "account", "p1", "BANK_CARD",
        "Zhang", "6222000012345678", "Test Bank", null, 0, T0.plusSeconds(4));
    var saved = service.savePortalPayoutAccount(save);
    assertEquals("****5678", saved.maskedPayoutAccountReference());
    assertEquals("Test Bank", saved.bankName());
    assertEquals(saved, service.savePortalPayoutAccount(new PromotionStore.SavePayoutAccount("save1", "account", "p1", "BANK_CARD",
        "Zhang", "6222000012345678", "Test Bank", null, 0, T0.plusSeconds(50))));
    assertEquals(1, service.portalPayoutAccounts("p1", 0, 20).total());
    assertEquals(0, service.portalPayoutAccounts("p2", 0, 20).total());
    projectPayoutAccount("disabled-portal", "p1", PayoutAccountStatus.DISABLED);
    assertEquals(PayoutAccountStatus.DISABLED, service.savePortalPayoutAccount(new PromotionStore.SavePayoutAccount(
        "edit-disabled", "disabled-portal", "p1", "BANK_CARD", "Zhang", "6222000012345678", "Test Bank", null, 1, T0.plusSeconds(5))).status());
    assertThrows(IllegalStateException.class, () -> service.savePortalPayoutAccount(new PromotionStore.SavePayoutAccount("steal", "account", "p2", "BANK_CARD",
        "Other", "6222000012345678", "Test Bank", null, 1, T0.plusSeconds(5))));
    assertThrows(IllegalStateException.class, () -> service.savePortalPayoutAccount(new PromotionStore.SavePayoutAccount("stale", "account", "p1", "BANK_CARD",
        "Other", "6222000012345678", "Test Bank", null, 0, T0.plusSeconds(5))));
    assertThrows(IllegalArgumentException.class, () -> service.savePortalPayoutAccount(new PromotionStore.SavePayoutAccount("bad", "bad", "p1", "WECHAT",
        "Zhang", "bad-phone", null, null, 0, T0.plusSeconds(5))));
    service.savePortalPayoutAccount(new PromotionStore.SavePayoutAccount("qr-save", "qr-account", "p1", "WECHAT",
        "Zhang", "13800001111", null, "original-qr", 0, T0.plusSeconds(5)));
    var qrEdited = service.savePortalPayoutAccount(new PromotionStore.SavePayoutAccount("qr-edit", "qr-account", "p1", "WECHAT",
        "Zhang Updated", "13800002222", null, null, 1, T0.plusSeconds(6)));
    assertEquals(true, qrEdited.qrCodeConfigured());
    assertEquals("original-qr", sql.queryForObject("SELECT qr_code_reference FROM promotion_payout_account WHERE payout_account_id='qr-account'", String.class));
    service.bindReferral(new PromotionStore.BindReferral("b2", "customer", "p2", 1, T0.plusSeconds(6)));
    assertEquals(0, service.portalDownlines("p1", 0, 20).total());
    assertEquals(1, service.portalDownlines("p2", 0, 20).total());
    assertEquals(0, service.portalRecharges("p1", 0, 20).total());
    service.setPromoterStatus(new PromotionStore.ChangePromoterStatus("off", "p1", PromoterStatus.DISABLED, "admin", T0.plusSeconds(7)));
    assertEquals(false, service.portalEligibility("p1").eligible());
    assertThrows(IllegalStateException.class, () -> service.portalOverview("p1"));
  }

  @Test
  void portalOverviewCountsDirectInvitesAndKeepsNetEarnedCommissionAcrossRefundsAndRebinding() {
    approvePromoter("p1"); approvePromoter("p2");
    service.bindReferral(new PromotionStore.BindReferral("direct", "customer", "p1", 0, T0.plusSeconds(2)));
    service.bindReferral(new PromotionStore.BindReferral("indirect", "child", "customer", 0, T0.plusSeconds(3)));
    service.freezeRecharge(new PromotionStore.FreezeRecharge("freeze", "order", "lot", "customer", 1, 1,
        new BigDecimal("2000"), T0.plusSeconds(4)));
    service.settleConsumption(new PromotionStore.SettleConsumption("release", "allocation", "lot", new BigDecimal("1500"), false, T0.plusSeconds(5)));
    assertEquals("150.0000", service.portalOverview("p1").releasedCommissionAmount4());
    assertEquals("200.0000", service.portalOverview("p1").totalCommissionAmount4());
    projectPayoutAccount("payout", "p1", PayoutAccountStatus.ACTIVE);
    service.requestWithdrawal(new PromotionStore.RequestWithdrawal("withdraw", "withdrawal", "p1", new BigDecimal("100"), "payout", T0.plusSeconds(6)));
    service.decideWithdrawal(new PromotionStore.DecideWithdrawal("approve", "withdrawal", "admin", true, null, T0.plusSeconds(7)));
    service.completeWithdrawal(new PromotionStore.CompleteWithdrawal("paid", "withdrawal", "admin", T0.plusSeconds(8)));
    // 50 frozen clawback + 100 released reversal (50 available + 50 cash debt).
    service.refundRecharge(new PromotionStore.RefundRecharge("refund", "lot", new BigDecimal("1500"), T0.plusSeconds(9)));
    var result = service.portalOverview("p1");
    assertEquals(1, result.levelOneCount()); assertEquals(0, result.levelTwoCount());
    assertEquals(1, result.downlineCount());
    assertEquals("50.0000", result.releasedCommissionAmount4());
    assertEquals("50.0000", result.totalCommissionAmount4());
    assertEquals(new BigDecimal("50.0000"), result.commission().cashDebt());
    assertEquals("0.0000", service.portalOverview("p2").totalCommissionAmount4());
    service.bindReferral(new PromotionStore.BindReferral("move", "customer", "p2", 1, T0.plusSeconds(10)));
    service.bindReferral(new PromotionStore.BindReferral("move-child", "child", "p2", 1, T0.plusSeconds(11)));
    assertEquals(0, service.portalOverview("p1").downlineCount());
    assertEquals(2, service.portalOverview("p2").levelOneCount());
    assertEquals(0, service.portalOverview("p2").levelTwoCount());
    assertEquals("50.0000", service.portalOverview("p1").totalCommissionAmount4());
    assertEquals("50.0000", service.portalOverview("p1").releasedCommissionAmount4());
  }

  @Test
  void paidMonthsUsePaymentTimeInShanghaiAggregateBeforePagingAndExcludeOtherStatesAndOwners() {
    approvePromoter("p1"); approvePromoter("p2");
    service.bindReferral(new PromotionStore.BindReferral("bind-months", "customer", "p1", 0, T0));
    service.freezeRecharge(new PromotionStore.FreezeRecharge("fund-months", "order-months", "lot-months", "customer", 1, 1, new BigDecimal("30000"), T0.plusSeconds(1)));
    service.settleConsumption(new PromotionStore.SettleConsumption("release-months", "allocation-months", "lot-months", new BigDecimal("30000"), true, T0.plusSeconds(2)));
    projectPayoutAccount("payout-months", "p1", PayoutAccountStatus.ACTIVE);
    for (int i = 0; i < 5; i++) {
      service.requestWithdrawal(new PromotionStore.RequestWithdrawal("month-request-" + i, "month-withdrawal-" + i, "p1", new BigDecimal("100.01"), "payout-months", T0.plusSeconds(10 + i)));
      if (i == 4) continue;
      service.decideWithdrawal(new PromotionStore.DecideWithdrawal("month-approve-" + i, "month-withdrawal-" + i, "admin", true, null, T0.plusSeconds(20 + i)));
      if (i == 3) continue;
      var payment = new PromotionStore.CompleteWithdrawal("month-paid-" + i, "month-withdrawal-" + i, "admin",
          Instant.parse(i == 0 ? "2026-09-30T15:59:59Z" : "2026-09-30T16:00:00Z"));
      service.completeWithdrawal(payment); service.completeWithdrawal(payment);
    }
    var first = service.portalPaidMonths("p1", 0, 1);
    assertEquals(2, first.total()); assertEquals("300.0300", first.totalAmount4());
    assertEquals("2026-10", first.items().getFirst().month()); assertEquals("200.0200", first.items().getFirst().amount4());
    var second = service.portalPaidMonths("p1", 1, 1);
    assertEquals("2026-09", second.items().getFirst().month()); assertEquals("100.0100", second.items().getFirst().amount4());
    assertEquals(first.totalAmount4(), second.totalAmount4());
    assertEquals(0, service.portalPaidMonths("p1", 2, 1).items().size());
    assertEquals("0.0000", service.portalPaidMonths("p2", 0, 20).totalAmount4());
    assertEquals(0, service.portalPaidMonths("p2", 0, 20).total());
    service.bindReferral(new PromotionStore.BindReferral("rebind-months", "customer", "p2", 1, T0.plusSeconds(40)));
    assertEquals("300.0300", service.portalPaidMonths("p1", 0, 20).totalAmount4());
    assertThrows(IllegalArgumentException.class, () -> service.portalPaidMonths("p1", -1, 20));
  }

  @Test
  void portalWithdrawalDateFilterPrecedesPaginationAndCountAndKeepsOwnersSeparate() {
    approvePromoter("p1"); approvePromoter("p2");
    service.bindReferral(new PromotionStore.BindReferral("bind", "customer", "p1", 0, T0.plusSeconds(2)));
    service.freezeRecharge(new PromotionStore.FreezeRecharge("fund", "order", "lot", "customer", 1, 1, new BigDecimal("30000"), T0.plusSeconds(3)));
    service.settleConsumption(new PromotionStore.SettleConsumption("release", "allocation", "lot", new BigDecimal("30000"), true, T0.plusSeconds(4)));
    projectPayoutAccount("payout", "p1", PayoutAccountStatus.ACTIVE);
    for (int i = 0; i < 23; i++) {
      Instant requested = i < 2 ? T0.plusSeconds(10 + i) : T0.plusSeconds(35 * 86400L + i);
      service.requestWithdrawal(new PromotionStore.RequestWithdrawal("request-" + i, "withdrawal-" + i, "p1", new BigDecimal("100"), "payout", requested));
    }
    Instant end = Instant.parse("2026-10-01T00:00:00Z");
    var september = service.portalWithdrawals("p1", T0, end, 0, 1);
    assertEquals("200.0000", september.totalAmount4());
    assertEquals("200.0000", service.portalWithdrawals("p1", T0, end, 1, 1).totalAmount4());
    assertEquals("0.0000", service.portalWithdrawals("p2", T0, end, 0, 1).totalAmount4());
    assertEquals(2, september.total()); assertEquals("withdrawal-1", september.items().getFirst().id());
    assertEquals("withdrawal-0", service.portalWithdrawals("p1", T0, end, 1, 1).items().getFirst().id());
    assertEquals(0, service.portalWithdrawals("p2", T0, end, 0, 20).total());
    assertEquals(23, service.portalWithdrawals("p1", null, null, 0, 20).total());
    assertEquals(1, service.portalWithdrawals("p1", T0.plusSeconds(10), T0.plusSeconds(11), 0, 20).total());
    assertThrows(IllegalArgumentException.class, () -> service.portalWithdrawals("p1", end, T0, 0, 20));
    assertEquals(23, service.listWithdrawals(null, "p1", 0, 20).total());
  }

  @Test
  void financialRecordsShareExactDateBoundsAndFullRangeTotals() {
    approvePromoter("p1"); approvePromoter("p2");
    service.bindReferral(new PromotionStore.BindReferral("bind", "customer", "p1", 0, T0));
    for (int i = 0; i < 3; i++) {
      service.freezeRecharge(new PromotionStore.FreezeRecharge("fund-" + i, "order-" + i, "lot-" + i,
          "customer", 1, 1, new BigDecimal("123.4567"), T0.plusSeconds(i)));
      service.settleConsumption(new PromotionStore.SettleConsumption("used-" + i, "allocation-" + i,
          "lot-" + i, new BigDecimal("12.3456"), true, T0.plusSeconds(i)));
    }
    var paid = service.portalRecharges("p1", T0, T0.plusSeconds(2), 0, 1);
    assertEquals(2, paid.total()); assertEquals(1, paid.items().size());
    assertEquals("246.9134", paid.totalAmount4());
    assertEquals("246.9134", service.portalRecharges("p1", T0, T0.plusSeconds(2), 1, 1).totalAmount4());
    var used = service.portalConsumptions("p1", T0, T0.plusSeconds(2), 0, 1);
    assertEquals(2, used.total()); assertEquals("24.6912", used.totalAmount4());
    assertEquals("24.6912", service.portalConsumptions("p1", T0, T0.plusSeconds(2), 1, 1).totalAmount4());
    assertEquals("0.0000", service.portalRecharges("p2", T0, null, 0, 1).totalAmount4());
    assertEquals("0.0000", service.portalConsumptions("p2", T0, null, 0, 1).totalAmount4());
    assertEquals(0, service.portalConsumptions("p1", T0.plusSeconds(3), null, 0, 1).total());
    assertThrows(IllegalArgumentException.class, () -> service.portalRecharges("p1", T0, T0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> service.portalConsumptions("p1", T0.plusSeconds(1), T0, 0, 1));
    sql.update("UPDATE promotion_commission_ledger SET successful_paid_amount4 = NULL WHERE allocation_id = ?", "allocation-0");
    assertEquals(null, service.portalConsumptions("p1", T0, T0.plusSeconds(2), 0, 1).totalAmount4());
    assertEquals("12.3456", service.portalConsumptions("p1", T0.plusSeconds(1), T0.plusSeconds(2), 0, 1).totalAmount4());
    sql.update("UPDATE promotion_funding_benefit SET occurred_at = ?", T0.toString());
    assertEquals("order-0", service.adminPromoterRecharges("p1", 0, 1).items().getFirst().orderId());
    assertEquals("order-1", service.adminPromoterRecharges("p1", 1, 1).items().getFirst().orderId());
  }

  private void approvePromoter(String userId) {
    projectEligibility(userId, "ENABLED");
    service.submitApplication(new PromotionStore.SubmitApplication(
        "submit-" + userId, "application-" + userId, userId,
        "share", "community", true, T0));
    service.reviewApplication(new PromotionStore.ReviewApplication(
        "review-" + userId, "application-" + userId, "admin-1", ApplicationDecision.APPROVE,
        null, T0.plusSeconds(1)));
  }

  private void projectEligibility(String userId, String status) {
    service.projectApplicantEligibility(new PromotionStore.ProjectApplicantEligibility(
        "eligibility-" + userId + "-v1", userId, "PERSONAL", status, T0.minusSeconds(60),
        1, T0.minusSeconds(30)));
  }

  private void projectPayoutAccount(String accountId, String ownerId, PayoutAccountStatus status) {
    service.projectPayoutAccount(new PromotionStore.ProjectPayoutAccount(
        "payout-event-" + accountId, accountId, ownerId, "BANK_CARD", "Zhang",
        "provider-token-5678", status, 1, T0.plusSeconds(4)));
  }
}
