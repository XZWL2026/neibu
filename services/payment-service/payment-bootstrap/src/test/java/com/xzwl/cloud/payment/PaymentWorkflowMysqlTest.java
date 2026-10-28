package com.xzwl.cloud.payment;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.payment.application.workflow.*;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_MYSQL_URL",matches=".+")
class PaymentWorkflowMysqlTest {
  @Test void durablePaidLateRefundAndConcurrentReplay() throws Exception {
    String root=System.getenv("PAYMENT_TEST_MYSQL_URL");
    if(!root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"))throw new IllegalArgumentException("local database required");
    String schema="abp_payment_"+UUID.randomUUID().toString().replace("-","");
    String databaseUser=System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_USERNAME","root");
    String databasePassword=System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PASSWORD","");
    var sql=new JdbcTemplate(new DriverManagerDataSource(root,databaseUser,databasePassword));
    sql.execute("CREATE DATABASE "+schema+" COLLATE utf8mb4_0900_bin");
    var props=Map.<String,Object>of("spring.datasource.url",root+schema,"spring.datasource.username",databaseUser,"spring.datasource.password",databasePassword,
        "spring.flyway.locations","filesystem:"+java.nio.file.Path.of("..","payment-infrastructure","src","main","resources","db","migration").toAbsolutePath().normalize(),
        "abp.internal-token","test-only-service-token-0000000000000000","payment.worker-enabled","false",
        "payment.user-internal-token","test-only-user-token-0000000000000000000");
    var clock=new MutableClock();var provider=new Provider();var fulfillment=new Fulfillment();
    String orderId,lateOrderId;
    try{
      try(var app=start(props)){
        var store=app.getBean(PaymentWorkflowStore.class);
        var workflow=new PaymentWorkflow(store,provider,fulfillment,clock);
        assertThrows(IllegalArgumentException.class,()->workflow.create(new PaymentWorkflow.NewRecharge(
            "overflow","overflow-user",new BigDecimal("9999999999999999999999999999999999"),RechargeType.API,null)));
        var request=new PaymentWorkflow.NewRecharge("request-1","user",new BigDecimal("5000.12"),RechargeType.API,null);
        var order=workflow.create(request);orderId=order.id();
        race(8,()->{
          assertEquals(order.id(),workflow.create(new PaymentWorkflow.NewRecharge(UUID.randomUUID().toString(),"user",
              new BigDecimal("5000.1200"),RechargeType.API,null)).id());return null;
        });
        assertEquals(new BigDecimal("10.1234"),order.rateSnapshot4());
        assertEquals(orderId,workflow.create(new PaymentWorkflow.NewRecharge("request-alias","user",new BigDecimal("5000.1200"),RechargeType.API,null)).id());
        assertEquals(orderId,workflow.create(request).id());
        assertTrue(fulfillment.credits.isEmpty());
        assertThrows(IllegalArgumentException.class,()->workflow.callback(new byte[]{0},Map.of()));
        var verified=new PaymentProvider.VerifiedPayment(orderId,"provider-tx-1",new BigDecimal("5000.1200"),"CNY",clock.instant());
        assertThrows(IllegalArgumentException.class,()->store.confirm(
            new PaymentProvider.VerifiedPayment(order.id(),"bad-amount",BigDecimal.ONE,"CNY",clock.instant()),clock.instant()));
        assertThrows(IllegalArgumentException.class,()->store.confirm(
            new PaymentProvider.VerifiedPayment(order.id(),"bad-currency",order.amount4(),"USD",clock.instant()),clock.instant()));
        race(8,()->{store.confirm(verified,clock.instant());return null;});
        fulfillment.failCredit.set(true);
        workflow.processPending();
        assertFalse(store.find(orderId).orElseThrow().fulfilled());
      }
      clock.now=clock.now.plusSeconds(10);
      try(var app=start(props)){
        var store=app.getBean(PaymentWorkflowStore.class);var workflow=new PaymentWorkflow(store,provider,fulfillment,clock);
        workflow.processPending();
        assertTrue(store.find(orderId).orElseThrow().fulfilled());
        assertEquals(1,fulfillment.credits.size());
        assertEquals(orderId,workflow.create(new PaymentWorkflow.NewRecharge("request-alias","user",new BigDecimal("5000.1200"),RechargeType.API,null)).id());
        var late=workflow.create(new PaymentWorkflow.NewRecharge("late","late-user",new BigDecimal("50"),RechargeType.PERSONAL,null));lateOrderId=late.id();
        clock.now=clock.now.plusSeconds(901);workflow.processPending();
        assertEquals("EXPIRED",store.find(late.id()).orElseThrow().status());
        store.confirm(new PaymentProvider.VerifiedPayment(late.id(),"late-tx",late.amount4(),"CNY",clock.instant()),clock.instant());
        workflow.processPending();
        assertEquals("REFUNDED",store.find(late.id()).orElseThrow().status());
        assertNotNull(store.find(late.id()).orElseThrow().refundedAt());
        assertFalse(fulfillment.credits.contains(late.id()));
        workflow.refund(orderId,"user");provider.failRefund.set(true);workflow.processPending();
        assertEquals("REFUND_PENDING",store.find(orderId).orElseThrow().status());
        assertNull(store.find(orderId).orElseThrow().refundedAt());
        clock.now=clock.now.plusSeconds(10);workflow.processPending();
        assertEquals("REFUNDED",store.find(orderId).orElseThrow().status());
        var refundTime=store.find(orderId).orElseThrow().refundedAt();
        assertNotNull(refundTime);workflow.processPending();
        assertEquals(refundTime,store.find(orderId).orElseThrow().refundedAt());
        assertEquals(Set.of(orderId),fulfillment.reservations);
        assertEquals(2,provider.refunded.size());
        assertThrows(IllegalStateException.class,()->store.confirm(new PaymentProvider.VerifiedPayment(orderId,"different",new BigDecimal("5000.1200"),"CNY",clock.instant()),clock.instant()));
        var contender1=workflow.create(new PaymentWorkflow.NewRecharge("choice-1","choice-user",new BigDecimal("5000"),RechargeType.API,null));
        var contender2=workflow.create(new PaymentWorkflow.NewRecharge("choice-2","choice-user",new BigDecimal("50"),RechargeType.PERSONAL,null));
        try(var pool=Executors.newFixedThreadPool(2)){
          var one=pool.submit(()->store.confirm(new PaymentProvider.VerifiedPayment(contender1.id(),"choice-tx-1",contender1.amount4(),"CNY",clock.instant()),clock.instant()));
          var two=pool.submit(()->store.confirm(new PaymentProvider.VerifiedPayment(contender2.id(),"choice-tx-2",contender2.amount4(),"CNY",clock.instant()),clock.instant()));
          assertEquals(Set.of("PAID","REFUND_PENDING"),Set.of(one.get().status(),two.get().status()));
        }
        // A late callback carrying an on-time, verified provider payment still fulfills.
        var delayed=workflow.create(new PaymentWorkflow.NewRecharge("delayed","delayed-user",new BigDecimal("5000"),RechargeType.API,null));
        Instant actuallyPaid=clock.instant();clock.now=clock.now.plusSeconds(901);store.expire(clock.instant());
        store.confirm(new PaymentProvider.VerifiedPayment(delayed.id(),"delayed-tx",delayed.amount4(),"CNY",actuallyPaid),clock.instant());
        assertEquals("PAID",store.find(delayed.id()).orElseThrow().status());
        var firstLease=store.claim(delayed.id(),clock.instant()).orElseThrow();
        assertTrue(store.claim(delayed.id(),clock.instant()).isEmpty());
        clock.now=clock.now.plusSeconds(61);
        var recovered=store.claim(delayed.id(),clock.instant()).orElseThrow();
        store.complete(firstLease,"stale-key");
        assertFalse(store.find(delayed.id()).orElseThrow().fulfilled());
        store.complete(recovered,"current-key");
        assertEquals("current-key",store.find(delayed.id()).orElseThrow().keyId());
      }
      // Replay the exact migration on historical rows without the new column.
      var dataSource=new DriverManagerDataSource(root+schema,databaseUser,databasePassword);
      var historical=new JdbcTemplate(dataSource);
      historical.execute("ALTER TABLE payment_order DROP COLUMN refunded_at");
      new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.FileSystemResource(
          "../payment-infrastructure/src/main/resources/db/migration/V9__refund_completion_time.sql")).execute(dataSource);
      assertEquals(historical.queryForObject("SELECT occurred_at FROM payment_promotion_delivery WHERE order_id=?",String.class,orderId),
          historical.queryForObject("SELECT refunded_at FROM payment_order WHERE id=?",String.class,orderId));
      assertNull(historical.queryForObject("SELECT refunded_at FROM payment_order WHERE id=?",String.class,lateOrderId));
    }finally{sql.execute("DROP DATABASE "+schema);}
  }
  static void race(int count,Callable<?> work)throws Exception{
    try(var pool=Executors.newFixedThreadPool(count)){
      var gate=new CountDownLatch(1);var jobs=new ArrayList<Future<?>>();
      for(int i=0;i<count;i++)jobs.add(pool.submit(()->{gate.await();return work.call();}));
      gate.countDown();for(var job:jobs)job.get(20,TimeUnit.SECONDS);
    }
  }
  static class MutableClock extends Clock{
    Instant now=Instant.parse("2026-09-07T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}
    public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
  }
  static class Provider implements PaymentProvider{
    final Set<String> refunded=ConcurrentHashMap.newKeySet();final AtomicBoolean failRefund=new AtomicBoolean();
    public Checkout create(Charge charge){return new Checkout(charge.orderId(),"https://pay.example.test/"+charge.orderId());}
    public VerifiedPayment verify(byte[] raw,Map<String,List<String>> headers){throw new IllegalArgumentException("invalid signature");}
    public String refund(Refund refund){refunded.add(refund.refundId());if(failRefund.getAndSet(false))throw new IllegalStateException("response lost");return "ref-"+refund.orderId();}
  }
  static class Fulfillment implements RechargeFulfillmentPort{
    final Set<String> credits=ConcurrentHashMap.newKeySet(),reservations=ConcurrentHashMap.newKeySet();final AtomicBoolean failCredit=new AtomicBoolean();
    public BigDecimal currentRate(){return new BigDecimal("10.1234");}
    public String ensurePrimary(RechargeOrder order){return "key-"+order.userId();}
    public void credit(RechargeOrder order,String key){credits.add(order.id());if(failCredit.getAndSet(false))throw new IllegalStateException("response lost");}
    public void reserveRefund(RechargeOrder order){reservations.add(order.id());}
  }
  static org.springframework.context.ConfigurableApplicationContext start(Map<String,Object> props){
    var env=new StandardEnvironment();env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);env.getPropertySources().addFirst(new MapPropertySource("test",props));
    return new SpringApplicationBuilder(TemplateApplication.class).environment(env).web(WebApplicationType.NONE).initializers(ctx->ctx.getBeanFactory().registerSingleton("testFulfillment",new Fulfillment())).run("--spring.config.location=classpath:/application.yml");
  }
}
