package com.xzwl.cloud.billing;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.billing.application.ledger.*;
import com.xzwl.cloud.billing.application.pricing.MediaPriceStore;
import com.xzwl.cloud.billing.application.recharge.*;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.pricing.*;
import com.xzwl.cloud.billing.infrastructure.*;
import com.xzwl.cloud.billing.infrastructure.persistence.BillingDatabase;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_MYSQL_URL",matches=".+")
class ApiClientPricingMysqlTest {
  @Test void persistedOverridesDriveQuoteChargeAndCatalogWithStableReplayAndAudits() {
    String root=System.getenv("BILLING_TEST_MYSQL_URL");assertTrue(root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    String schema="api_prices_"+UUID.randomUUID().toString().replace("-","");
    String user=System.getenv("BILLING_TEST_MYSQL_USERNAME"),password=System.getenv("BILLING_TEST_MYSQL_PASSWORD");
    var sql=new JdbcTemplate(new DriverManagerDataSource(root,user,password));sql.execute("CREATE DATABASE "+schema);
    try(var app=BillingLedgerMysqlTest.start(Map.of("spring.datasource.url",root+schema,"spring.datasource.username",user,"spring.datasource.password",password,
        "abp.internal-token","test-only-service-token-0000000000000000"))) {
      var store=app.getBean(MediaPriceStore.class);var ledger=app.getBean(BillingLedgerStore.class);
      var defaults=new MediaPrice("VIDEO","API","model","720p","SECOND",null,"2.0000",true);
      var override=new MediaPrice("VIDEO","API","model","720p","SECOND",null,"0.1234",true);
      store.save(store.load().version(),List.of(defaults));
      var initial=store.loadApi("owner");assertEquals(List.of(defaults),initial.prices());
      var saved=store.saveApi("owner","request","admin",initial.version(),List.of(override));
      assertEquals(saved,new JdbcMediaPriceStore(app.getBean(JdbcTemplate.class),app.getBean(org.springframework.transaction.PlatformTransactionManager.class)).loadApi("owner"));
      assertEquals(saved,store.saveApi("owner","request","admin",initial.version(),List.of(override)));
      assertThrows(IllegalStateException.class,()->store.saveApi("owner","request","admin",initial.version(),List.of()));
      assertThrows(IllegalStateException.class,()->store.saveApi("owner","stale","admin",initial.version(),List.of()));
      assertThrows(IllegalArgumentException.class,()->store.saveApi("owner","unknown","admin",saved.version(),List.of(new MediaPrice("IMAGE","API","unknown","2k","IMAGE",null,"1",true))));
      var billing=new MybatisMediaBilling(app.getBean(BillingDatabase.class),id->new MediaBilling.Key("key",id,"API","API",true),PricingCatalog.empty(),app.getBean(RechargeRateStore.class),ledger,store);
      app.getBean(CreditPaidRechargeHandler.class).handle(new CreditPaidRechargeCommand("paid","owner","key","key",Decimal4.of("1000"),Decimal4.of("1"),Instant.now()));
      var charge=new MediaBilling.Charge("api-task","owner","VIDEO","model","720p",1,5);
      assertEquals("0.6170",billing.quote(new MediaBilling.QuoteRequest("owner","VIDEO","model","720p",1,5)).amount().toPlainString());
      assertEquals("0.1234",billing.availablePrices("owner").getFirst().amountPerUnit().toPlainString());
      assertEquals("2.0000",billing.availablePrices("another").getFirst().amountPerUnit().toPlainString());
      var receipt=billing.charge(charge);assertEquals("0.6170",receipt.amount().toPlainString());
      store.saveApi("owner","inherit","admin",saved.version(),List.of());
      assertEquals("2.0000",billing.availablePrices("owner").getFirst().amountPerUnit().toPlainString());
      assertEquals(receipt,billing.charge(charge));assertEquals(1,ledger.entries("key",true,100).stream().filter(e->e.kind().equals("CONSUME")).count());
      assertEquals(2,store.apiAudit("owner").size());
      var daily=app.getBean(ConsumptionStore.class).apiDailyFacts(List.of(new ConsumptionStore.DirectoryOwner("owner","key")),Instant.now().minusSeconds(60),Instant.now().plusSeconds(60)).getFirst();
      assertEquals(1,daily.todayCalls());assertEquals(5,daily.todaySeconds());assertEquals("0.6170",daily.todayAmount4());assertNotNull(daily.lastCallAt());
      billing.refund(charge.operationId(),new MediaBilling.Refund("owner","refund",receipt.points()));
      assertEquals(Decimal4.of("1000"),ledger.balance("key").availablePoints4());
      var local=app.getBean(JdbcTemplate.class);
      local.update("UPDATE wallet_ledger SET recorded_at=? WHERE request_id='refund'",Instant.now().plusSeconds(3600).toString());
      var beforeRefund=app.getBean(ConsumptionStore.class).apiDailyFacts(List.of(new ConsumptionStore.DirectoryOwner("owner","key")),Instant.now().minusSeconds(60),Instant.now().plusSeconds(60)).getFirst();
      assertEquals("0.6170",beforeRefund.todayAmount4(),"future refunds must not alter today's amount");
      local.update("UPDATE wallet_ledger SET recorded_at=? WHERE request_id='refund'",Instant.now().toString());

      store.save(store.load().version(),List.of(new MediaPrice("VIDEO","API","model","720p","SECOND",null,"0",true)));
      billing.charge(new MediaBilling.Charge("free-api-task","owner","VIDEO","model","720p",1,5));
      var zeroIncluded=app.getBean(ConsumptionStore.class).apiDailyFacts(List.of(new ConsumptionStore.DirectoryOwner("owner","key")),Instant.now().minusSeconds(60),Instant.now().plusSeconds(60)).getFirst();
      assertEquals(2,zeroIncluded.todayCalls());assertEquals(10,zeroIncluded.todaySeconds());assertEquals("0.0000",zeroIncluded.todayAmount4());
      ledger.consume("key","legacy-task",Decimal4.of("1"));
      var unknown=app.getBean(ConsumptionStore.class).apiDailyFacts(List.of(new ConsumptionStore.DirectoryOwner("owner","key")),Instant.now().minusSeconds(60),Instant.now().plusSeconds(60)).getFirst();
      assertEquals(3,unknown.todayCalls());assertNull(unknown.todaySeconds());assertNull(unknown.todayAmount4());
      app.getBean(JdbcTemplate.class).update("INSERT INTO media_charge_receipt(operation_id,owner_id,key_id,request_facts,amount4,rate4,points4,account_type) VALUES('undated','owner','key',?,0,1,0,'API')","owner\nVIDEO\nmodel\n720p\n1\n5");
      var undated=app.getBean(ConsumptionStore.class).apiDailyFacts(List.of(new ConsumptionStore.DirectoryOwner("owner","key")),Instant.now().minusSeconds(60),Instant.now().plusSeconds(60)).getFirst();
      assertNull(undated.todayCalls());assertNull(undated.todaySeconds());assertNull(undated.lastCallAt());

      for(var accountType:List.of("PERSONAL","ENTERPRISE")) {
        String owner="owner-"+accountType,key="key-"+accountType;
        var userDefault=new MediaPrice("IMAGE",accountType,"image","2k","IMAGE",null,"2",true);
        var userOverride=new MediaPrice("IMAGE",accountType,"image","2k","IMAGE",null,"0.1234",true);
        store.save(store.load().version(),List.of(userDefault));
        var userPrices=store.saveOwner(owner,accountType,"save-"+accountType,"admin",0,List.of(userOverride));
        app.getBean(CreditPaidRechargeHandler.class).handle(new CreditPaidRechargeCommand("paid-"+accountType,owner,key,key,Decimal4.of("1000"),Decimal4.of("1"),Instant.now()));
        var userBilling=new MybatisMediaBilling(app.getBean(BillingDatabase.class),id->new MediaBilling.Key(key,id,accountType,"MEDIA",true),PricingCatalog.empty(),app.getBean(RechargeRateStore.class),ledger,store);
        var userCharge=new MediaBilling.Charge("task-"+accountType,owner,"IMAGE","image","2k",2,0);
        assertEquals("0.2468",userBilling.quote(new MediaBilling.QuoteRequest(owner,"IMAGE","image","2k",2,0)).amount().toPlainString());
        assertEquals("0.1234",userBilling.availablePrices(owner).getFirst().amountPerUnit().toPlainString());
        var userReceipt=userBilling.charge(userCharge);
        assertEquals("0.2468",userReceipt.amount().toPlainString());
        assertEquals(Decimal4.of("1000").subtract(Decimal4.of(userReceipt.points())),ledger.balance(key).availablePoints4());
        store.saveOwner(owner,accountType,"reset-"+accountType,"admin",userPrices.version(),List.of());
        assertEquals("4.0000",userBilling.quote(new MediaBilling.QuoteRequest(owner,"IMAGE","image","2k",2,0)).amount().toPlainString());
        assertEquals(userReceipt,userBilling.charge(userCharge));
        assertEquals(1,ledger.entries(key,true,100).stream().filter(e->e.kind().equals("CONSUME")).count());
        assertEquals(2,store.ownerAudit(owner,accountType).size());
        assertTrue(store.apiAudit(owner).isEmpty());
      }

    } finally {sql.execute("DROP DATABASE "+schema);}
  }
}
