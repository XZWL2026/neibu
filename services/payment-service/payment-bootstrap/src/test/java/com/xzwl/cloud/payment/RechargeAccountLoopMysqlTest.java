package com.xzwl.cloud.payment;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.payment.application.workflow.*;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_MYSQL_URL",matches=".+")
class RechargeAccountLoopMysqlTest {
  @Test void apiEntryCreatesNoIdentityOrKeyUntilVerifiedPaymentAndFulfillsOnce() throws Exception {
    withStore((store,clock)->{
      var keys=new java.util.concurrent.atomic.AtomicInteger();
      var creditCalls=new java.util.concurrent.atomic.AtomicInteger();
      var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment(){
        public String ensurePrimary(RechargeOrder order){
          assertEquals("api-entry-owner",order.userId());assertEquals(RechargeType.API,order.rechargeType());
          assertEquals("PAID",order.status());keys.incrementAndGet();return super.ensurePrimary(order);
        }
        public void credit(RechargeOrder order,String key){creditCalls.incrementAndGet();super.credit(order,key);}
      };
      var workflow=new PaymentWorkflow(store,new PaymentWorkflowMysqlTest.Provider(),fulfillment,clock);
      var controller=new com.xzwl.cloud.payment.interfaces.http.PaymentController(workflow,id->true);
      var session=new org.springframework.mock.web.MockHttpServletRequest();session.setUserPrincipal(()->"api-entry-owner");session.addUserRole("API_USER");
      var request=new com.xzwl.cloud.payment.interfaces.http.PaymentController.NewRecharge("api-entry-request","5000.0000",RechargeType.API,null);
      var opened=controller.create(request,session);
      assertEquals("api-entry-owner",opened.userId());assertEquals("PENDING",opened.status());
      assertEquals(opened.orderId(),controller.create(request,session).orderId());
      workflow.processPending();
      assertNull(store.accountType("api-entry-owner"));assertTrue(store.firstRecharge("api-entry-owner"));
      assertNull(store.find(opened.orderId()).orElseThrow().keyId());assertEquals(0,keys.get());assertEquals(0,creditCalls.get());
      var order=store.find(opened.orderId()).orElseThrow();
      assertEquals("PAID",confirm(store,order,clock));assertEquals("API",store.accountType("api-entry-owner"));
      assertEquals(0,keys.get());assertFalse(store.find(order.id()).orElseThrow().fulfilled());
      workflow.processPending();confirm(store,order,clock);workflow.processPending();
      var paid=store.find(order.id()).orElseThrow();
      assertTrue(paid.fulfilled());assertEquals("key-api-entry-owner",paid.keyId());assertFalse(store.firstRecharge("api-entry-owner"));
      assertEquals(1,keys.get());assertEquals(1,creditCalls.get());assertEquals(1,workflow.history("api-entry-owner").size());
    });
  }
  @Test void rulesRejectBelowMinimumAndFractionalCents() throws Exception {
    withStore((store,clock)->{
      for(var type:RechargeType.values()) {
        String min=switch(type){case PERSONAL->"50";case ENTERPRISE->"1000";case API->"5000";};
        assertThrows(IllegalArgumentException.class,()->store.create(request("low-"+type,type,new BigDecimal(min).subtract(new BigDecimal("0.01")).toPlainString()),BigDecimal.TEN,clock.instant()));
        assertThrows(IllegalArgumentException.class,()->store.create(request("fraction-"+type,type,min+".001"),BigDecimal.TEN,clock.instant()));
        assertEquals(new BigDecimal(min).setScale(4),store.create(request("valid-"+type,type,min),BigDecimal.TEN,clock.instant()).amount4());
      }
    });
  }
  @Test void adminAmountsIgnorePlatformMinimumBeforeAndAfterFirstRecharge() throws Exception {
    withStore((store,clock)->{
      var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment();
      var workflow=new PaymentWorkflow(store,new PaymentWorkflowMysqlTest.Provider(),fulfillment,clock);
      for(var type:RechargeType.values()) {
        String owner="small-admin-"+type;
        for(String invalid:List.of("0","-1","0.001"))assertThrows(IllegalArgumentException.class,()->workflow.manual(new PaymentWorkflow.ManualRecharge("invalid-"+type+invalid,owner,new BigDecimal(invalid),type,null,"audit","admin")));
        for(int i=0;i<2;i++) {
          var request=new PaymentWorkflow.ManualRecharge("small-"+type+i,owner,new BigDecimal("0.01"),type,null,"audit","admin");
          var pending=workflow.manual(request);assertEquals("REVIEW_PENDING",pending.status());assertFalse(pending.fulfilled());
          assertEquals(pending.id(),workflow.manual(request).id());
          workflow.confirmManual(pending.id(),"reviewer");workflow.processPending();
          workflow.confirmManual(pending.id(),"reviewer");workflow.processPending();
          var paid=store.find(pending.id()).orElseThrow();assertEquals("PAID",paid.status());assertTrue(paid.fulfilled());assertEquals(new BigDecimal("0.0100"),paid.amount4());
          assertThrows(IllegalArgumentException.class,()->store.create(new PaymentWorkflow.NewRecharge("platform-low-"+pending.id(),owner,new BigDecimal("0.01"),type,null),BigDecimal.TEN,clock.instant()));
        }
      }
      assertEquals(6,fulfillment.credits.size());
    });
  }
  @Test void concurrentSameIdentityGetsOnlyOneFirstRecharge()throws Exception {
    withStore((store,clock)->{
      var a=store.create(request("same",RechargeType.PERSONAL,"50"),BigDecimal.TEN,clock.instant());
      var b=store.create(new PaymentWorkflow.NewRecharge("second","same",new BigDecimal("60"),RechargeType.PERSONAL,null),BigDecimal.TEN,clock.instant());
      var results=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
      try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
        var jobs=List.of(pool.submit(()->results.add(confirm(store,a,clock))),pool.submit(()->results.add(confirm(store,b,clock))));
        for(var job:jobs)job.get();
      }
      assertEquals(Set.of("PAID","REFUND_PENDING"),results);
    });
  }
  @Test void manualReviewIsDurableAuditedAndNeverUsesProvider()throws Exception {
    withStore((store,clock)->{

      var provider=new PaymentWorkflowMysqlTest.Provider(){
        public Checkout create(Charge c){fail("manual must not create provider charge");return null;}
        public String refund(Refund r){fail("manual must not create provider refund");return null;}
      };
      var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment(){
        public void manageAccount(PaymentWorkflowStore.AccountChange change){assertEquals("API",change.accountType());}
      };
      var workflow=new PaymentWorkflow(store,provider,fulfillment,clock);
      var request=new PaymentWorkflow.ManualRecharge("manual-request","manual-owner",new BigDecimal("50"),RechargeType.PERSONAL,null,"Bank receipt checked","submitter");
      var opened=workflow.manual(request);
      assertEquals("REVIEW_PENDING",opened.status());assertEquals("MANUAL",opened.source());
      assertNull(store.accountType("manual-owner"));assertNull(opened.transactionId());
      assertEquals(opened.id(),workflow.manual(request).id());
      assertThrows(IllegalStateException.class,()->workflow.manual(new PaymentWorkflow.ManualRecharge("manual-request","manual-owner",new BigDecimal("50"),RechargeType.PERSONAL,null,"changed","submitter")));
      workflow.adminAccount("api-owner","assign-api-owner","API",true,"primary",true);
      var apiPending=workflow.manual(new PaymentWorkflow.ManualRecharge("api-manual","api-owner",new BigDecimal("5000"),RechargeType.API,null,"API bank receipt","submitter"),false);
      assertEquals("REVIEW_PENDING",apiPending.status());assertFalse(apiPending.fulfilled());assertNull(apiPending.keyId());
      workflow.processPending();assertTrue(fulfillment.credits.isEmpty());assertTrue(workflow.options("api-owner").firstRecharge());
      assertThrows(IllegalStateException.class,()->store.confirm(new PaymentProvider.VerifiedPayment(opened.id(),"fake-provider",opened.amount4(),"CNY",clock.instant()),clock.instant()));
      PaymentWorkflowMysqlTest.race(5,()->{workflow.confirmManual(opened.id(),"reviewer");return null;});
      var reviewed=store.find(opened.id()).orElseThrow();
      assertEquals("reviewer",reviewed.confirmedBy());assertEquals("submitter",reviewed.submittedBy());assertEquals("Bank receipt checked",reviewed.remark());
      assertNull(reviewed.transactionId());assertNull(reviewed.refundReference());assertFalse(reviewed.fulfilled());
      fulfillment.failCredit.set(true);workflow.processPending();assertFalse(store.find(opened.id()).orElseThrow().fulfilled());
      clock.now=clock.now.plusSeconds(10);workflow.processPending();workflow.confirmManual(opened.id(),"another-reviewer");workflow.processPending();
      assertTrue(store.find(opened.id()).orElseThrow().fulfilled());assertEquals(1,fulfillment.credits.size());
      assertEquals("reviewer",store.find(opened.id()).orElseThrow().confirmedBy());
      assertThrows(IllegalStateException.class,()->workflow.refund(opened.id(),"manual-owner"));
      assertEquals(1,workflow.history("manual-owner").size());assertEquals(0,workflow.history("other").size());
      assertEquals(2,workflow.adminHistory().size());
      assertFalse(workflow.options("manual-owner").firstRecharge());
      assertEquals("200.0000",workflow.options("manual-owner").rules().stream().filter(r->r.accountType().equals("PERSONAL")).findFirst().orElseThrow().minimumAmount4());
    });
  }
  @Test void reviewCompetitionUsesSameAccountLockAndHistoryIsBounded()throws Exception {
    withStore((store,clock)->{
      var manual=new PaymentWorkflow.ManualRecharge("review-compete","mixed-owner",new BigDecimal("50"),RechargeType.PERSONAL,null,"Verified transfer","admin");
      var review=store.manual(manual,BigDecimal.TEN,clock.instant());
      var provider=store.create(new PaymentWorkflow.NewRecharge("provider-compete","mixed-owner",new BigDecimal("5000"),RechargeType.API,null),BigDecimal.TEN,clock.instant());
      store.confirmManual(review.id(),"reviewer",clock.instant());
      assertEquals("REFUND_PENDING",confirm(store,provider,clock));
      assertEquals("PERSONAL",store.accountType("mixed-owner"));
      assertThrows(IllegalStateException.class,()->store.manual(new PaymentWorkflow.ManualRecharge("new-wrong","mixed-owner",new BigDecimal("5000"),RechargeType.API,null,"receipt","admin"),BigDecimal.TEN,clock.instant()));
      var first=store.manual(new PaymentWorkflow.ManualRecharge("review-first","review-owner",new BigDecimal("50"),RechargeType.PERSONAL,null,"receipt","admin"),BigDecimal.TEN,clock.instant());
      var next=store.manual(new PaymentWorkflow.ManualRecharge("review-second","review-owner",new BigDecimal("60"),RechargeType.PERSONAL,null,"receipt","admin"),BigDecimal.TEN,clock.instant());
      store.confirmManual(first.id(),"reviewer",clock.instant());
      assertEquals("PAID",store.confirmManual(next.id(),"reviewer",clock.instant()).status());
      assertEquals("reviewer",store.find(next.id()).orElseThrow().confirmedBy());
      for(int i=0;i<102;i++)store.manual(new PaymentWorkflow.ManualRecharge("history-"+i,"history-owner",new BigDecimal("50"),RechargeType.PERSONAL,null,"receipt "+i,"admin"),BigDecimal.TEN,clock.instant().plusSeconds(i));
      var history=store.history("history-owner");assertEquals(100,history.size());
      assertEquals("receipt 101",history.getFirst().remark());assertEquals("receipt 2",history.getLast().remark());
      assertEquals(100,store.history(null).size());
    });
  }
  @Test void administratorIdentityIsRecoverableDoesNotFakeFirstRechargeAndPreservesOrderSnapshots()throws Exception{
    withStore((store,clock)->{
      var failed=new java.util.concurrent.atomic.AtomicBoolean(true);var remote=new HashSet<String>();
      var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment(){
        public void manageAccount(PaymentWorkflowStore.AccountChange change){remote.add(change.requestId());if(failed.getAndSet(false))throw new IllegalStateException("lost Access ACK");}
      };
      var workflow=new PaymentWorkflow(store,new PaymentWorkflowMysqlTest.Provider(),fulfillment,clock);
      assertThrows(IllegalStateException.class,()->workflow.adminAccount("admin-user","create","PERSONAL",true,"admin",true));
      assertThrows(IllegalStateException.class,()->workflow.options("admin-user"));
      assertThrows(IllegalStateException.class,()->workflow.adminAccount("admin-user","different","PERSONAL",true,"admin",true));
      workflow.processPending();assertTrue(store.pendingAccountChanges().isEmpty());
      assertTrue(workflow.options("admin-user").firstRecharge());assertEquals("PERSONAL",workflow.options("admin-user").accountType());
      workflow.adminAccount("admin-user","create","PERSONAL",true,"admin",true);assertEquals(Set.of("create"),remote);
      assertThrows(IllegalStateException.class,()->workflow.adminAccount("admin-user","create","PERSONAL",false,"admin",true));
      assertThrows(IllegalStateException.class,()->workflow.adminAccount("admin-user","create","PERSONAL",true,"other",true));
      assertThrows(PaymentWorkflow.Forbidden.class,()->workflow.adminAccount("admin-user","denied","ENTERPRISE",true,"normal",false));
      assertThrows(IllegalArgumentException.class,()->store.manual(new PaymentWorkflow.ManualRecharge("low-admin","admin-user",new BigDecimal("0.001"),RechargeType.PERSONAL,null,"audit","admin"),BigDecimal.TEN,clock.instant()));
      var order=workflow.manual(new PaymentWorkflow.ManualRecharge("paid-admin","admin-user",new BigDecimal("50"),RechargeType.PERSONAL,null,"audit","admin"));
      assertThrows(IllegalStateException.class,()->workflow.adminAccount("admin-user","pending-change","ENTERPRISE",false,"admin",true));
      assertThrows(IllegalStateException.class,()->workflow.adminAccount("admin-user","pending-api","API",true,"admin",true));
      workflow.confirmManual(order.id(),"reviewer");
      assertThrows(IllegalStateException.class,()->workflow.adminAccount("admin-user","fulfilling-api","API",true,"admin",true));
      assertThrows(IllegalStateException.class,()->workflow.adminAccount("admin-user","fulfilling-change","ENTERPRISE",false,"admin",true));
      workflow.processPending();assertTrue(store.find(order.id()).orElseThrow().fulfilled());
      workflow.adminAccount("admin-user","enterprise","ENTERPRISE",false,"admin",true);
      assertEquals("ENTERPRISE",workflow.options("admin-user").accountType());assertFalse(workflow.options("admin-user").firstRecharge());
      assertEquals("2000.0000",workflow.options("admin-user").rules().stream().filter(r->r.accountType().equals("ENTERPRISE")).findFirst().orElseThrow().minimumAmount4());
      assertEquals(RechargeType.PERSONAL,store.find(order.id()).orElseThrow().rechargeType());assertEquals(new BigDecimal("50.0000"),store.find(order.id()).orElseThrow().amount4());
      assertThrows(PaymentWorkflow.Forbidden.class,()->workflow.adminAccount("admin-user","normal-back","PERSONAL",false,"normal",false));
      workflow.adminAccount("admin-user","back","PERSONAL",false,"admin",true);
      workflow.adminAccount("admin-user","enterprise","ENTERPRISE",false,"admin",true);assertEquals("PERSONAL",workflow.options("admin-user").accountType(),"old request must not revert current identity");
      assertThrows(PaymentWorkflow.Forbidden.class,()->workflow.adminAccount("admin-user","normal-api","API",true,"normal",false));
      assertThrows(IllegalArgumentException.class,()->workflow.adminAccount("admin-user","api-no-key","API",false,"admin",true));
      workflow.adminAccount("admin-user","convert-api","API",true,"admin",true);
      assertEquals("API",workflow.options("admin-user").accountType());assertFalse(workflow.options("admin-user").firstRecharge());
      assertEquals(RechargeType.PERSONAL,store.find(order.id()).orElseThrow().rechargeType());
      workflow.adminAccount("admin-user","convert-api","API",true,"admin",true);
      assertTrue(store.pendingAccountChanges().isEmpty());
      var api=store.create(request("existing-api",RechargeType.API,"5000"),BigDecimal.TEN,clock.instant());confirm(store,api,clock);workflow.processPending();
      for(String target:List.of("PERSONAL","ENTERPRISE","API"))assertThrows(PaymentWorkflow.Forbidden.class,()->workflow.adminAccount("existing-api","normal-"+target,target,true,"normal",false));
      workflow.adminAccount("existing-api","keep-api","API",true,"admin",true);
      var pending=workflow.manual(new PaymentWorkflow.ManualRecharge("pending-api-recharge","existing-api",new BigDecimal("50"),RechargeType.API,null,"audit","admin"));
      assertThrows(IllegalStateException.class,()->workflow.adminAccount("existing-api","api-to-personal","PERSONAL",false,"admin",true));
      workflow.rejectManual(pending.id(),"reviewer");
      workflow.adminAccount("existing-api","api-to-personal","PERSONAL",false,"admin",true);
      assertEquals("PERSONAL",workflow.options("existing-api").accountType());assertFalse(workflow.options("existing-api").firstRecharge());
      workflow.adminAccount("existing-api","keep-api","API",true,"admin",true);assertEquals("PERSONAL",workflow.options("existing-api").accountType());
      workflow.adminAccount("existing-api","again-api","API",true,"admin",true);
      workflow.adminAccount("existing-api","api-to-enterprise","ENTERPRISE",false,"admin",true);
      assertEquals("ENTERPRISE",workflow.options("existing-api").accountType());
      assertEquals(RechargeType.API,store.find(api.id()).orElseThrow().rechargeType());assertEquals(new BigDecimal("5000.0000"),store.find(api.id()).orElseThrow().amount4());
      assertTrue(store.pendingAccountChanges().isEmpty());
    });
  }
  @Test void rejectedManualReviewIsDurableIdempotentAndNeverCredits() throws Exception {
    withStore((store,clock)->{
      var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment();
      var workflow=new PaymentWorkflow(store,new PaymentWorkflowMysqlTest.Provider(),fulfillment,clock);
      var request=new PaymentWorkflow.ManualRecharge("reject-request","reject-owner",new BigDecimal("50"),RechargeType.PERSONAL,null,"receipt","submitter");
      var order=workflow.manual(request);
      var rejected=workflow.rejectManual(order.id(),"reviewer");
      assertEquals("REJECTED",rejected.status());assertEquals("reviewer",rejected.rejectedBy());assertEquals(clock.instant(),rejected.rejectedAt());
      assertNull(rejected.confirmedBy());assertNull(rejected.paidAt());assertFalse(rejected.fulfilled());
      assertEquals(rejected,workflow.rejectManual(order.id(),"another-reviewer"));
      assertEquals(rejected,workflow.manual(request));
      assertThrows(IllegalStateException.class,()->workflow.confirmManual(order.id(),"admin"));
      workflow.processPending();assertTrue(fulfillment.credits.isEmpty());assertTrue(store.pending(clock.instant()).isEmpty());
      assertNull(store.accountType("reject-owner"));assertTrue(store.firstRecharge("reject-owner"));
      var online=store.create(request("provider-reject",RechargeType.PERSONAL,"50"),BigDecimal.TEN,clock.instant());
      assertThrows(IllegalStateException.class,()->workflow.rejectManual(online.id(),"admin"));
    });
  }
  @Test void approvalAndRejectionSerializeToOneTerminalDecision() throws Exception {
    withStore((store,clock)->{
      var workflow=new PaymentWorkflow(store,new PaymentWorkflowMysqlTest.Provider(),new PaymentWorkflowMysqlTest.Fulfillment(),clock);
      var order=workflow.manual(new PaymentWorkflow.ManualRecharge("race-request","race-owner",new BigDecimal("50"),RechargeType.PERSONAL,null,"receipt","submitter"));
      var results=new java.util.concurrent.ConcurrentLinkedQueue<String>();
      try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
        var approve=pool.submit(()->{try{results.add(workflow.confirmManual(order.id(),"approve").status());}catch(IllegalStateException conflict){results.add("CONFLICT");}});
        var reject=pool.submit(()->{try{results.add(workflow.rejectManual(order.id(),"reject").status());}catch(IllegalStateException conflict){results.add("CONFLICT");}});
        approve.get();reject.get();
      }
      var decided=store.find(order.id()).orElseThrow();
      assertTrue(Set.of("PAID","REJECTED").contains(decided.status()));assertTrue(results.contains("CONFLICT"));
      assertEquals(2,results.size());assertEquals(decided.status().equals("PAID")?1:0,store.pending(clock.instant()).size());
      if(decided.status().equals("PAID")){assertNull(decided.rejectedBy());assertThrows(IllegalStateException.class,()->workflow.rejectManual(order.id(),"admin"));}
      else {assertNull(decided.confirmedBy());assertNull(store.accountType("race-owner"));}
    });
  }
  @Test void paidRechargeSwitchesPersonalAndEnterpriseWithoutNameAndRetainsThresholds()throws Exception {
    withStore((store,clock)->{
      var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment();
      var workflow=new PaymentWorkflow(store,new PaymentWorkflowMysqlTest.Provider(),fulfillment,clock);
      var first=store.create(new PaymentWorkflow.NewRecharge("switch-first","switch-owner",new BigDecimal("1000"),RechargeType.ENTERPRISE,null),BigDecimal.TEN,clock.instant());
      assertNull(store.accountType("switch-owner"));assertEquals("PAID",confirm(store,first,clock));workflow.processPending();
      var personal=store.create(new PaymentWorkflow.NewRecharge("switch-personal","switch-owner",new BigDecimal("200"),RechargeType.PERSONAL,null),BigDecimal.TEN,clock.instant());
      assertEquals("ENTERPRISE",store.accountType("switch-owner"));assertEquals("PAID",confirm(store,personal,clock));workflow.processPending();
      assertEquals("PERSONAL",store.accountType("switch-owner"));
      assertThrows(IllegalArgumentException.class,()->store.create(new PaymentWorkflow.NewRecharge("too-low","switch-owner",new BigDecimal("1000"),RechargeType.ENTERPRISE,null),BigDecimal.TEN,clock.instant()));
      var enterprise=store.create(new PaymentWorkflow.NewRecharge("switch-enterprise","switch-owner",new BigDecimal("2000"),RechargeType.ENTERPRISE,null),new BigDecimal("12.3456"),clock.instant());
      assertEquals("PERSONAL",store.accountType("switch-owner"));assertEquals("PAID",confirm(store,enterprise,clock));workflow.processPending();
      confirm(store,personal,clock);workflow.processPending();
      assertEquals("ENTERPRISE",store.accountType("switch-owner"));assertFalse(store.firstRecharge("switch-owner"));
      assertEquals(3,fulfillment.credits.size());assertEquals(new BigDecimal("24691.2000"),store.find(enterprise.id()).orElseThrow().amount4().multiply(enterprise.rateSnapshot4()).setScale(4));
      assertThrows(IllegalStateException.class,()->store.create(new PaymentWorkflow.NewRecharge("switch-api","switch-owner",new BigDecimal("5000"),RechargeType.API,null),BigDecimal.TEN,clock.instant()));
    });
  }
  @Test void normalAdminCannotChangeEnterpriseIdentityThroughManualRecharge() throws Exception {
    withStore((store,clock)->{
      var initial=store.create(request("enterprise-protected",RechargeType.ENTERPRISE,"1000"),BigDecimal.TEN,clock.instant());
      confirm(store,initial,clock);
      var command=new PaymentWorkflow.ManualRecharge("downgrade","enterprise-protected",new BigDecimal("200"),RechargeType.PERSONAL,null,"receipt","admin");
      var approved=store.manual(command,BigDecimal.TEN,clock.instant(),true);
      assertAll(
        ()->assertThrows(PaymentWorkflow.Forbidden.class,()->store.manual(new PaymentWorkflow.ManualRecharge("normal-downgrade","enterprise-protected",new BigDecimal("200"),RechargeType.PERSONAL,null,"receipt","normal"),BigDecimal.TEN,clock.instant(),false)),
        ()->assertThrows(PaymentWorkflow.Forbidden.class,()->store.confirmManual(approved.id(),"normal",clock.instant(),false)));
      assertEquals("ENTERPRISE",store.accountType("enterprise-protected"));
      assertEquals("PAID",store.confirmManual(approved.id(),"admin",clock.instant(),true).status());
    });
  }
  static String confirm(PaymentWorkflowStore store,RechargeOrder order,PaymentWorkflowMysqlTest.MutableClock clock){return store.confirm(new PaymentProvider.VerifiedPayment(order.id(),"tx-"+order.id(),order.amount4(),"CNY",clock.instant()),clock.instant()).status();}
  static PaymentWorkflow.NewRecharge request(String user,RechargeType type,String amount){return new PaymentWorkflow.NewRecharge("r-"+user,user,new BigDecimal(amount),type,type==RechargeType.ENTERPRISE?"Real Enterprise":null);}
  interface Check{void run(PaymentWorkflowStore store,PaymentWorkflowMysqlTest.MutableClock clock)throws Exception;}
  static void withStore(Check check)throws Exception {
    String root=System.getenv("PAYMENT_TEST_MYSQL_URL");
    if(!root.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"))throw new IllegalArgumentException("local database required");
    String schema="recharge_loop_"+UUID.randomUUID().toString().replace("-","");
    String user=System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_USERNAME","root"),password=System.getenv().getOrDefault("PAYMENT_TEST_MYSQL_PASSWORD","");
    var sql=new JdbcTemplate(new DriverManagerDataSource(root,user,password));sql.execute("CREATE DATABASE "+schema+" COLLATE utf8mb4_0900_bin");
    var props=Map.<String,Object>of("spring.datasource.url",root+schema,"spring.datasource.username",user,"spring.datasource.password",password,
      "spring.flyway.locations","filesystem:"+java.nio.file.Path.of("..","payment-infrastructure","src","main","resources","db","migration").toAbsolutePath().normalize(),
      "abp.internal-token","test-only-service-token-0000000000000000","payment.worker-enabled","false","payment.user-internal-token","test-only-user-token-0000000000000000000");
    try{
      List<RechargeOrder> persisted;
      try(var app=PaymentWorkflowMysqlTest.start(props)){
        var store=app.getBean(PaymentWorkflowStore.class);check.run(store,new PaymentWorkflowMysqlTest.MutableClock());persisted=store.history(null);
      }
      try(var app=PaymentWorkflowMysqlTest.start(props)){assertEquals(persisted,app.getBean(PaymentWorkflowStore.class).history(null));}
    }
    finally{sql.execute("DROP DATABASE "+schema);}
  }
}
