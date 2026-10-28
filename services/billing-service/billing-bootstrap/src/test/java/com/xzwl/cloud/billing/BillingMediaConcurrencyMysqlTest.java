package com.xzwl.cloud.billing;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.billing.application.ledger.*;
import com.xzwl.cloud.billing.application.recharge.*;
import com.xzwl.cloud.billing.domain.money.Decimal4;
import com.xzwl.cloud.billing.domain.pricing.*;
import com.xzwl.cloud.billing.infrastructure.MybatisMediaBilling;
import com.xzwl.cloud.billing.infrastructure.persistence.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="BILLING_TEST_MYSQL_URL",matches=".+")
class BillingMediaConcurrencyMysqlTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"PERSONAL","API"})
  void retryCannotRejectWhileAnEarlierDebitCanStillCommit(String accountType) throws Exception {
    String root=System.getenv("BILLING_TEST_MYSQL_URL");
    assertTrue(root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]{1,5}/"));
    String schema="billing_media_race_"+UUID.randomUUID().toString().replace("-","");
    String user=System.getenv().getOrDefault("BILLING_TEST_MYSQL_USERNAME","root");
    String password=System.getenv().getOrDefault("BILLING_TEST_MYSQL_PASSWORD","");
    var sql=new JdbcTemplate(new DriverManagerDataSource(root,user,password));sql.execute("CREATE DATABASE "+schema);
    try(var app=BillingLedgerMysqlTest.start(Map.of("spring.datasource.url",root+schema,
        "spring.datasource.username",user,"spring.datasource.password",password,
        "abp.internal-token","test-only-service-token-0000000000000000"))) {
      var ledger=app.getBean(BillingLedgerStore.class);
      app.getBean(CreditPaidRechargeHandler.class).handle(new CreditPaidRechargeCommand("paid","owner","key","key",Decimal4.of("1000"),Decimal4.of("1"),Instant.now()));
      var db=app.getBean(BillingDatabase.class);
      var rates=app.getBean(RechargeRateStore.class);
      var prices=PricingCatalog.empty().withPrice(new PricingEntry(new PricingKey(accountType,"model","standard"),Decimal4.of("1")));
      var checked=new CountDownLatch(1);
      MediaBilling.Keys enabled=owner->{checked.countDown();return new MediaBilling.Key("key",owner,accountType,accountType.equals("API")?"API":"MEDIA",true);};
      MediaBilling.Keys disabled=owner->new MediaBilling.Key("key",owner,accountType,accountType.equals("API")?"API":"MEDIA",false);
      var initial=new MybatisMediaBilling(db,enabled,prices,rates,ledger);
      var retry=new MybatisMediaBilling(db,disabled,prices,rates,ledger);
      var command=new MediaBilling.Charge("task","owner","IMAGE","model","standard",1,0);
      var walletLocked=new CountDownLatch(1);var releaseWallet=new CountDownLatch(1);
      try(var threads=Executors.newFixedThreadPool(3)) {
        var lock=threads.submit(()->db.transaction(()->{
          db.mapper(BillingMappers.Wallets.class).lock("key");walletLocked.countDown();
          try {if(!releaseWallet.await(10,TimeUnit.SECONDS))throw new AssertionError("wallet release timeout");}
          catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
          return true;
        }));
        try {
          assertTrue(walletLocked.await(10,TimeUnit.SECONDS));
          var first=threads.submit(()->initial.charge(command));
          assertTrue(checked.await(10,TimeUnit.SECONDS));
          var retryStarted=new CountDownLatch(1);
          var second=threads.submit(()->{retryStarted.countDown();return retry.charge(command);});
          assertTrue(retryStarted.await(10,TimeUnit.SECONDS));
          // A timed-out client may retry while the first server request still waits on this wallet.
          assertThrows(TimeoutException.class,()->second.get(200,TimeUnit.MILLISECONDS));
          releaseWallet.countDown();
          var receipt=first.get(10,TimeUnit.SECONDS);
          assertEquals(receipt,second.get(10,TimeUnit.SECONDS));
          assertEquals(Decimal4.of("1000").subtract(Decimal4.of(receipt.points())),ledger.balance("key").availablePoints4());
          assertEquals(1,ledger.entries("key",true,100).stream().filter(e->e.kind().equals("CONSUME")).count());
          var half=receipt.points().divide(java.math.BigDecimal.valueOf(2));
          retry.refund("task",new MediaBilling.Refund("owner","refund-part",half));
          retry.refund("task",new MediaBilling.Refund("owner","refund-part",half));
          assertEquals(Decimal4.of("1000").subtract(Decimal4.of(half)),ledger.balance("key").availablePoints4());
          retry.refund("task",new MediaBilling.Refund("owner","refund-rest",half));
          assertEquals(Decimal4.of("1000"),ledger.balance("key").availablePoints4());
        } finally {releaseWallet.countDown();lock.get(10,TimeUnit.SECONDS);}
      }
    } finally {sql.execute("DROP DATABASE "+schema);}
  }
}
