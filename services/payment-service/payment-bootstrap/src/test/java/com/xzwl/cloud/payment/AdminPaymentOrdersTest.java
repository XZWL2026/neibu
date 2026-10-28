package com.xzwl.cloud.payment;

import com.fasterxml.jackson.databind.*;
import com.xzwl.cloud.payment.application.identity.SessionVerifier;
import com.xzwl.cloud.payment.application.workflow.*;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import com.xzwl.cloud.payment.interfaces.http.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class AdminPaymentOrdersTest {
  static final String PATH="/api/v1/admin/payments/orders",TO="2026-09-07T00:00:00Z";
  final ObjectMapper json=new ObjectMapper();
  static MockMvc mvc(PaymentWorkflow workflow){
    SessionVerifier sessions=(cookie,csrf,write)->{
      if(cookie==null)throw new SessionVerifier.Rejected(401);
      assertFalse(write,"order reads must not request write authorization");
      return new SessionVerifier.Identity("owner",Set.of(cookie));
    };
    return MockMvcBuilders.standaloneSetup(new PaymentController(workflow,id->true))
      .setControllerAdvice(new PaymentErrors())
      .addFilters(new PaymentSecurityFilter(sessions,"test-only-service-token-0000000000000000")).build();
  }
  @Test void requiresAdminAndValidBoundedPaginationBeforeReadingOrders()throws Exception{
    var mvc=mvc(null);
    assertEquals(401,mvc.perform(get(PATH).param("to",TO)).andReturn().getResponse().getStatus());
    for(String role:List.of("USER","API_USER","PROMOTER"))
      assertEquals(403,mvc.perform(get(PATH).param("to",TO).header("Cookie",role)).andReturn().getResponse().getStatus());
    for(String role:List.of("ADMIN","NORMAL_ADMIN")){
      for(String query:List.of("","?to=invalid","?to=","?to=2026-09-07","?to=%2B1000000000-12-31T23:59:59.999999999Z",
          "?to="+TO+"&offset=-1","?to="+TO+"&offset=2147483648","?to="+TO+"&offset=no",
          "?to="+TO+"&limit=0","?to="+TO+"&limit=101","?to="+TO+"&limit=no"))
        assertEquals(400,mvc.perform(get(PATH+query).header("Cookie",role)).andReturn().getResponse().getStatus(),query);
    }
  }
  @Test @EnabledIfEnvironmentVariable(named="PAYMENT_TEST_MYSQL_URL",matches=".+")
  void readsEveryOrderAcrossPagesWithStableCutoffAndPersistedPaymentFacts()throws Exception{
    RechargeAccountLoopMysqlTest.withStore((store,clock)->{
      var workflow=new PaymentWorkflow(store,new PaymentWorkflowMysqlTest.Provider(),new PaymentWorkflowMysqlTest.Fulfillment(),clock);
      var created=new ArrayList<RechargeOrder>();
      for(int i=0;i<103;i++)created.add(store.manual(new PaymentWorkflow.ManualRecharge("page-"+i,"owner",new BigDecimal("50.01"),RechargeType.PERSONAL,null,"receipt","admin"),new BigDecimal("10.1234"),clock.instant().minusSeconds(i%3)));
      var paid=workflow.create(new PaymentWorkflow.NewRecharge("provider-order","provider-owner",new BigDecimal("50.01"),RechargeType.PERSONAL,null));
      created.add(paid);
      Instant paidAt=clock.instant().plusSeconds(5);
      store.confirm(new PaymentProvider.VerifiedPayment(paid.id(),"actual-transaction",paid.amount4(),"CNY",paidAt),paidAt);
      clock.now=paidAt;
      workflow.processPending();workflow.refund(paid.id(),paid.userId());workflow.processPending();
      var expected=created.stream().sorted(Comparator.comparing(RechargeOrder::createdAt).thenComparing(RechargeOrder::id).reversed()).map(RechargeOrder::id).toList();
      var before=created.stream().map(order->store.find(order.id()).orElseThrow()).toList();
      var pendingBefore=store.pending(clock.instant());
      var mvc=mvc(workflow);
      var response=mvc.perform(get(PATH).param("to",TO).header("Cookie","NORMAL_ADMIN")).andReturn().getResponse();
      assertEquals(200,response.getStatus(),response.getContentAsString());assertEquals("no-store",response.getHeader("Cache-Control"));
      var first=json.readTree(response.getContentAsString());
      assertEquals(104,first.path("total").asInt());assertEquals(0,first.path("offset").asInt());assertEquals(100,first.path("limit").asInt());assertEquals(100,first.path("items").size());
      store.manual(new PaymentWorkflow.ManualRecharge("after-cutoff","owner",new BigDecimal("50"),RechargeType.PERSONAL,null,"later","admin"),BigDecimal.TEN,clock.instant().plusMillis(1));
      var second=page(mvc,100,100);assertEquals(104,second.path("total").asInt());assertEquals(100,second.path("offset").asInt());assertEquals(4,second.path("items").size());
      var items=new ArrayList<JsonNode>();first.path("items").forEach(items::add);second.path("items").forEach(items::add);
      assertEquals(expected,items.stream().map(item->item.path("order").path("orderId").asText()).toList());
      assertEquals(first,page(mvc,0,100));assertEquals(expected.getFirst(),page(mvc,0,1).path("items").get(0).path("order").path("orderId").asText());
      var beyond=page(mvc,Integer.MAX_VALUE,100);assertEquals(104,beyond.path("total").asInt());assertTrue(beyond.path("items").isEmpty());
      var empty=mvc.perform(get(PATH).param("to",Instant.parse(TO).minusSeconds(3).toString()).header("Cookie","ADMIN")).andReturn().getResponse();
      assertEquals(0,json.readTree(empty.getContentAsString()).path("total").asInt());
      var paidView=items.stream().filter(item->item.path("order").path("orderId").asText().equals(paid.id())).findFirst().orElseThrow();
      assertEquals(store.find(paid.id()).orElseThrow().refundedAt().toString(),paidView.path("refundedAt").asText());
      assertNotEquals(paidAt.toString(),paidView.path("refundedAt").asText());
      assertEquals(paidAt.toString(),paidView.path("paidAt").asText());assertEquals("actual-transaction",paidView.path("transactionId").asText());
      assertEquals(paid.id(),paidView.path("providerOrderId").asText());assertEquals("key-provider-owner",paidView.path("keyId").asText());assertEquals("ref-"+paid.id(),paidView.path("refundReference").asText());
      assertEquals("REFUNDED",paidView.path("order").path("status").asText());assertEquals("50.0100",paidView.path("order").path("amount4").asText());
      assertEquals("10.1234",paidView.path("order").path("rateSnapshot4").asText());assertEquals("506.2712",paidView.path("order").path("expectedPoints4").asText());
      for(var item:items)if(!item.equals(paidView))for(String field:List.of("paidAt","transactionId","providerOrderId","keyId","refundReference","refundedAt"))
        assertTrue(item.has(field)&&item.get(field).isNull(),field+" must preserve unknown values");
      assertEquals(100,workflow.adminHistory().size());assertEquals(100,workflow.history("owner").size());
      assertEquals(before,created.stream().map(order->store.find(order.id()).orElseThrow()).toList());assertEquals(pendingBefore,store.pending(clock.instant()));
    });
  }
  JsonNode page(MockMvc mvc,int offset,int limit)throws Exception{
    var response=mvc.perform(get(PATH).param("to",TO).param("offset",Integer.toString(offset)).param("limit",Integer.toString(limit)).header("Cookie","ADMIN")).andReturn().getResponse();
    assertEquals(200,response.getStatus(),response.getContentAsString());return json.readTree(response.getContentAsString());
  }
}
