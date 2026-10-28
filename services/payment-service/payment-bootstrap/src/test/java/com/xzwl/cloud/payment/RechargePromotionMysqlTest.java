package com.xzwl.cloud.payment;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.payment.application.workflow.*;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_MYSQL_URL",matches=".+")
class RechargePromotionMysqlTest {
  @Test void promotionFailureNeverBlocksCreditAndRetriesOriginalFacts() throws Exception {
    RechargeAccountLoopMysqlTest.withStore((store,clock)->{
      var delivered=new ArrayList<PaymentWorkflowStore.PromotionDelivery>();
      var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment(){
        public void promote(PaymentWorkflowStore.PromotionDelivery delivery){
          delivered.add(delivery);if(delivered.size()==1)throw new IllegalStateException("lost response");
        }
      };
      var workflow=new PaymentWorkflow(store,new PaymentWorkflowMysqlTest.Provider(),fulfillment,clock);
      var order=workflow.manual(new PaymentWorkflow.ManualRecharge("manual","owner",new BigDecimal("100"),RechargeType.PERSONAL,null,"verified","admin"));
      assertTrue(store.pendingPromotions(clock.instant()).isEmpty());
      workflow.confirmManual(order.id(),"reviewer");workflow.processPending();workflow.processPromotions();
      assertTrue(store.find(order.id()).orElseThrow().fulfilled());assertEquals(1,fulfillment.credits.size());
      assertEquals(1,delivered.size());assertTrue(store.pendingPromotions(clock.instant()).isEmpty());
      clock.now=clock.now.plusSeconds(31);
      var resumed=new PaymentWorkflow(store,new PaymentWorkflowMysqlTest.Provider(),fulfillment,clock);
      resumed.processPromotions();resumed.processPromotions();
      assertEquals(2,delivered.size());assertEquals(delivered.getFirst(),delivered.getLast());
      assertEquals(order.createdAt(),delivered.getFirst().order().createdAt());
      assertEquals(1,fulfillment.credits.size());assertTrue(store.pendingPromotions(clock.instant()).isEmpty());
    });
  }
  @Test void staleCreditAcknowledgmentCannotEraseRefundDelivery() throws Exception {
    RechargeAccountLoopMysqlTest.withStore((store,clock)->{
      var order=store.create(new PaymentWorkflow.NewRecharge("online","owner",new BigDecimal("100"),RechargeType.PERSONAL,null),BigDecimal.TEN,clock.instant());
      RechargeAccountLoopMysqlTest.confirm(store,order,clock);
      store.complete(store.claim(order.id(),clock.instant()).orElseThrow(),"key");
      var credit=store.pendingPromotions(clock.instant()).getFirst();assertEquals(1,credit.version());
      store.requestRefund(order.id(),order.userId());
      store.complete(store.claim(order.id(),clock.instant()).orElseThrow(),"refund");
      store.completePromotion(credit);store.retryPromotion(credit,clock.instant());
      var refund=store.pendingPromotions(clock.instant()).getFirst();assertEquals(2,refund.version());
      assertEquals("REFUNDED",refund.order().status());
      assertNotNull(refund.occurredAt());store.completePromotion(refund);
      assertTrue(store.pendingPromotions(clock.instant()).isEmpty());
    });
  }
  @Test void httpDeliveryCarriesOriginalFactsAndValidatesAcknowledgment() throws Exception {
    var received=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/internal/v1/promotions/paid-recharges",exchange->{
      assertEquals("test-token",exchange.getRequestHeaders().getFirst("X-Service-Token"));
      var body=mapper.readTree(exchange.getRequestBody());received.add(body);
      byte[] result=("{\"orderId\":\""+(received.size()==1?"wrong":body.path("orderId").asText())+"\",\"resolution\":\"RESOLVED\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,result.length);
      exchange.getResponseBody().write(result);exchange.close();
    });
    server.start();
    try {
      RechargeAccountLoopMysqlTest.withStore((store,clock)->{
        var order=store.create(new PaymentWorkflow.NewRecharge("http","owner",new BigDecimal("100"),RechargeType.PERSONAL,null),BigDecimal.TEN,clock.instant());
        RechargeAccountLoopMysqlTest.confirm(store,order,clock);
        store.complete(store.claim(order.id(),clock.instant()).orElseThrow(),"key");
        var delivery=store.pendingPromotions(clock.instant()).getFirst();
        var client=new com.xzwl.cloud.payment.infrastructure.workflow.HttpRechargeFulfillment("http://127.0.0.1:1","http://127.0.0.1:1","http://127.0.0.1:"+server.getAddress().getPort(),"test-token");
        assertThrows(IllegalStateException.class,()->client.promote(delivery));client.promote(delivery);
        assertEquals(received.getFirst(),received.getLast());
        var body=received.getLast();assertEquals(order.createdAt().toString(),body.path("createdAt").asText());
        assertEquals("100.0000",body.path("amount4").asText());assertTrue(body.path("refundedAt").isNull());
        client.promote(new PaymentWorkflowStore.PromotionDelivery(delivery.order(),2,clock.instant()));
        assertEquals(clock.instant().toString(),received.getLast().path("refundedAt").asText());
      });
    } finally {server.stop(0);}
  }

}
