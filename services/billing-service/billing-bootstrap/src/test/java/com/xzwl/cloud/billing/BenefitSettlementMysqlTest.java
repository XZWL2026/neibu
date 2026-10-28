package com.xzwl.cloud.billing;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.time.Instant;
import java.math.BigDecimal;
import com.xzwl.cloud.billing.application.ledger.*;
import com.xzwl.cloud.billing.application.recharge.*;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.pricing.*;
import com.xzwl.cloud.billing.infrastructure.*;
import com.xzwl.cloud.billing.infrastructure.persistence.BillingDatabase;
import com.xzwl.cloud.billing.application.ledger.BenefitSettlementStore.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
@EnabledIfEnvironmentVariable(named="BILLING_TEST_MYSQL_URL",matches=".+")
class BenefitSettlementMysqlTest {
  @Test void successfulSettlementsHaveDurableStorage() {
    isolated(app -> assertEquals(1, app.getBean(JdbcTemplate.class).queryForObject(
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='billing_benefit_settlement'",Integer.class)));
  }
  static final Instant AT=Instant.parse("2026-09-15T00:00:00Z");
  @Test void partialSuccessUsesRealAllocationsAndKeepsLaterRefundsAcrossDeliveryOrder() {
    isolated(app -> {
      var store=store(app);var ledger=app.getBean(BillingLedgerStore.class);var media=seed(app);
      media.charge(new MediaBilling.Charge("task","owner","IMAGE","model","standard",1,0));
      assertTrue(store.pending(AT.plusSeconds(1)).isEmpty());
      media.refund("task",new MediaBilling.Refund("owner","task-refund",new BigDecimal("25")));
      var command=new Settlement("task","owner","PARTIAL_SUCCESS","100.0000","25.0000",AT);
      race(()->{store.settle(command);return null;});
      for(int i=0;i<100;i++)store.settle(command);
      var first=store.pending(AT.plusSeconds(1)).getFirst();
      assertEquals("75.0000",first.successfulPoints4());assertEquals("100.0000",first.originalPaidAmount4());assertEquals("1000.0000",first.originalPoints4());
      assertEquals("owner",first.walletOwnerUserId());assertEquals("task",first.settlementId());
      assertThrows(IllegalStateException.class,()->store.settle(new Settlement("task","owner","PARTIAL_SUCCESS","100","24",AT)));
      race(()->ledger.refund("key","later-refund","task",Decimal4.of("10")));
      assertEquals(2,store.pending(AT.plusSeconds(1)).size());
      assertEquals("10.0000",store.pending(AT.plusSeconds(1)).stream().filter(e->e.kind().equals("REVERSED")).findFirst().orElseThrow().reversedPoints4());
      assertEquals(Decimal4.of("935"),ledger.balance("key").availablePoints4());
      media.charge(new MediaBilling.Charge("late-delivery","owner","IMAGE","model","standard",1,0));
      ledger.refund("key","refund-before-delivery","late-delivery",Decimal4.of("30"));
      store.settle(new Settlement("late-delivery","owner","SUCCEEDED","100","0",AT));
      var later=store.pending(AT.plusSeconds(1)).stream().filter(e->e.settlementId().equals("late-delivery")).toList();
      assertEquals(2,later.size());assertTrue(later.stream().anyMatch(e->e.successfulPoints4().equals("100.0000")&&e.reversedPoints4().equals("30.0000")));
      assertEquals(4,store(app).pending(AT.plusSeconds(1)).size());
      var sql=app.getBean(JdbcTemplate.class);
      assertEquals(2,sql.queryForObject("SELECT COUNT(*) FROM billing_benefit_settlement",Integer.class));
      assertThrows(IllegalArgumentException.class,()->store.settle(new Settlement("task","other","SUCCEEDED","100","0",AT)));
    });
  }
  @Test void grantsAndReversalsAreAtomicConcurrentAndDebtOnlyOffsetsRewards() {
    isolated(app -> {
      var store=store(app);var ledger=app.getBean(BillingLedgerStore.class);
      var grant=reward("grant","GRANT","reward1","allocation1","5");
      race(()->store.reward(grant));for(int i=0;i<100;i++)assertEquals(new Receipt("reward-key","5.0000","0.0000","0.0000"),store.reward(grant));
      ledger.consume("reward-key","spend",Decimal4.of("2"));
      var reverse=reward("reverse","REVERSE","reward1","allocation1","5");race(()->store.reward(reverse));
      assertEquals(new Receipt("reward-key","0.0000","3.0000","2.0000"),store.reward(reverse));
      assertEquals(Decimal4.zero(),ledger.balance("reward-key").availablePoints4());
      var next=reward("next","GRANT","reward2","allocation2","4");
      assertEquals(new Receipt("reward-key","2.0000","0.0000","0.0000"),store.reward(next));
      assertEquals(Decimal4.of("2"),ledger.balance("reward-key").availablePoints4());
      assertEquals(3,app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM billing_reward_event",Integer.class));
      assertEquals(0,app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM funding_lot WHERE lot_type='REFERRAL_REWARD' AND benefit_eligible=true",Integer.class));
      var moved=new MybatisBenefitSettlementStore(app.getBean(BillingDatabase.class),owner->{throw new AssertionError("replay cannot resolve current key");});
      assertEquals(store.reward(grant),moved.reward(grant));
      assertThrows(IllegalStateException.class,()->store.reward(reward("grant","GRANT","reward1","allocation1","6")));
      assertThrows(IllegalArgumentException.class,()->store.reward(reward("negative","GRANT","r3","a3","-1")));
      assertThrows(IllegalStateException.class,()->store.reward(reward("premature","REVERSE","missing","a4","1")));
      assertThrows(IllegalArgumentException.class,()->store.reward(reward("extra-reverse","REVERSE","reward1","allocation1","1")));
      assertEquals(Decimal4.of("2"),ledger.balance("reward-key").availablePoints4());
    });
  }
  @Test void refundOfSpentRevokedRewardRecoversDebtWithoutRestoringInvalidPoints() {
    isolated(app -> {
      var store=store(app);var ledger=app.getBean(BillingLedgerStore.class);
      store.reward(reward("grant","GRANT","reward1","allocation1","5"));
      assertEquals("0.0000",store.debtPoints("never-granted"));
      ledger.consume("reward-key","pending-task",Decimal4.of("2"));
      store.reward(reward("reverse","REVERSE","reward1","allocation1","5"));
      assertEquals("2.0000",store.debtPoints("beneficiary"));
      race(()->ledger.refund("reward-key","failed-task-refund","pending-task",Decimal4.of("2")));
      assertEquals(Decimal4.zero(),ledger.balance("reward-key").availablePoints4());
      assertEquals("0.0000",store.debtPoints("beneficiary"));
      assertEquals(new Receipt("reward-key","4.0000","0.0000","0.0000"),store.reward(reward("next","GRANT","reward2","allocation2","4")));
    });
  }
  @Test void failedAndGiftConsumptionNeverCreatesBenefitsAndEnqueueFailureRollsBack() {
    isolated(app -> {
      var store=store(app);var media=seed(app);var sql=app.getBean(JdbcTemplate.class);
      media.charge(new MediaBilling.Charge("failed","owner","IMAGE","model","standard",1,0));
      media.refund("failed",new MediaBilling.Refund("owner","failed-refund",new BigDecimal("100")));
      store.settle(new Settlement("failed","owner","FAILED","100","100",AT));assertTrue(store.pending(AT).isEmpty());
      sql.update("UPDATE funding_lot SET lot_type='GIFT',benefit_eligible=false WHERE wallet_id='key'");
      media.charge(new MediaBilling.Charge("gift","owner","IMAGE","model","standard",1,0));
      store.settle(new Settlement("gift","owner","SUCCEEDED","100","0",AT));assertTrue(store.pending(AT).isEmpty());
      sql.update("UPDATE funding_lot SET lot_type='REAL_RECHARGE',benefit_eligible=true WHERE wallet_id='key'");
      media.charge(new MediaBilling.Charge("atomic","owner","IMAGE","model","standard",1,0));
      sql.execute("ALTER TABLE billing_benefit_delivery ADD CONSTRAINT reject_delivery CHECK (event_id='impossible')");
      assertThrows(RuntimeException.class,()->store.settle(new Settlement("atomic","owner","SUCCEEDED","100","0",AT)));
      assertEquals(0,sql.queryForObject("SELECT COUNT(*) FROM billing_benefit_settlement WHERE task_id='atomic'",Integer.class));
      sql.execute("ALTER TABLE billing_benefit_delivery DROP CHECK reject_delivery");
      store.settle(new Settlement("atomic","owner","SUCCEEDED","100","0",AT));assertEquals(1,store.pending(AT).size());
    });
  }
  static Reward reward(String id,String kind,String entitlement,String allocation,String points) {return new Reward(id,kind,entitlement,"beneficiary","settlement",allocation,points,AT);}
  static MybatisBenefitSettlementStore store(org.springframework.context.ConfigurableApplicationContext app) {
    return new MybatisBenefitSettlementStore(app.getBean(BillingDatabase.class),owner->new MediaBilling.Key("reward-key",owner,"PERSONAL","MEDIA",true));
  }
  static MediaBilling seed(org.springframework.context.ConfigurableApplicationContext app) {
    app.getBean(CreditPaidRechargeHandler.class).handle(new CreditPaidRechargeCommand("paid","owner","key","key",Decimal4.of("100"),Decimal4.of("10"),AT));
    var rates=app.getBean(RechargeRateStore.class);rates.save(new com.xzwl.cloud.billing.domain.recharge.RechargeRate(Decimal4.of("10"),rates.load().version()+1));
    return new MybatisMediaBilling(app.getBean(BillingDatabase.class),owner->new MediaBilling.Key("key",owner,"PERSONAL","MEDIA",true),
        PricingCatalog.empty().withPrice(new PricingEntry(new PricingKey("PERSONAL","model","standard"),Decimal4.of("10"))),rates,app.getBean(BillingLedgerStore.class));
  }
  static void race(java.util.concurrent.Callable<?> work) {try{BillingLedgerMysqlTest.race(8,work);}catch(Exception failure){throw new RuntimeException(failure);}}
  static void isolated(java.util.function.Consumer<org.springframework.context.ConfigurableApplicationContext> body) {
    String root=System.getenv("BILLING_TEST_MYSQL_URL");assertTrue(root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    String schema="launch_billing_"+UUID.randomUUID().toString().replace("-","");
    String user=System.getenv("BILLING_TEST_MYSQL_USERNAME"),password=System.getenv("BILLING_TEST_MYSQL_PASSWORD");
    var sql=new JdbcTemplate(new DriverManagerDataSource(root,user,password));sql.execute("CREATE DATABASE "+schema);
    try(var app=BillingLedgerMysqlTest.start(Map.of("spring.datasource.url",root+schema,"spring.datasource.username",user,
      "spring.datasource.password",password,"abp.internal-token","test-only-service-token-0000000000000000","billing.benefits.worker-enabled",false))) {body.accept(app);}
    finally {sql.execute("DROP DATABASE "+schema);}
  }
}
