package com.xzwl.cloud.payment;

import com.xzwl.cloud.payment.application.workflow.*;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_MYSQL_URL",matches=".+")
class CorporateTransferMysqlTest {
  static TransferProof proof(){return new TransferProof("receipt.pdf","application/pdf","%PDF-1.7\nreceipt".getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
  static PaymentWorkflow.CorporateRecharge request(String key,String owner){return new PaymentWorkflow.CorporateRecharge(key,owner,new BigDecimal("5000.12"),"银行转账",proof());}
  @Test void receiptPersistsAndConcurrentConfirmationCreditsOnceAfterRecovery()throws Exception{
    RechargeAccountLoopMysqlTest.withStore((store,clock)->{
      var fulfillment=new PaymentWorkflowMysqlTest.Fulfillment();
      var workflow=new PaymentWorkflow(store,null,fulfillment,clock);
      var order=workflow.corporate(request("transfer","api-owner"));
      assertEquals("REVIEW_PENDING",order.status());assertEquals("CORPORATE",order.source());assertEquals(RechargeType.API,order.rechargeType());
      assertFalse(order.fulfilled());assertTrue(store.pending(clock.instant()).isEmpty());assertNull(store.accountType("api-owner"));
      assertEquals(new BigDecimal("10.1234"),order.rateSnapshot4());assertEquals("api-owner",order.submittedBy());
      assertArrayEquals(proof().content(),store.transferProof(order.id()).content());
      PaymentWorkflowMysqlTest.race(5,()->{assertEquals(order.id(),workflow.corporate(request("transfer","api-owner")).id());return null;});
      assertEquals(1,store.corporateHistory(0,20).total());assertEquals("receipt.pdf",store.corporateHistory(0,20).items().getFirst().fileName());
      assertTrue(store.corporateHistory(1,20).items().isEmpty());
      assertThrows(IllegalStateException.class,()->workflow.corporate(request("transfer","other-owner")));
      assertThrows(IllegalStateException.class,()->workflow.corporate(new PaymentWorkflow.CorporateRecharge("transfer","api-owner",new BigDecimal("5001"),"银行转账",proof())));
      assertThrows(IllegalStateException.class,()->workflow.corporate(new PaymentWorkflow.CorporateRecharge("transfer","api-owner",order.amount4(),"changed",proof())));
      assertThrows(IllegalStateException.class,()->workflow.corporate(new PaymentWorkflow.CorporateRecharge("transfer","api-owner",order.amount4(),"银行转账",new TransferProof("receipt.pdf","application/pdf","%PDF-changed".getBytes()))));
      assertThrows(IllegalArgumentException.class,()->workflow.corporate(new PaymentWorkflow.CorporateRecharge("fractions","api-owner",new BigDecimal("5000.001"),"",proof())));
      assertThrows(PaymentWorkflow.Forbidden.class,()->workflow.confirmManual(order.id(),"normal",false));
      assertEquals("REVIEW_PENDING",store.find(order.id()).orElseThrow().status());
      assertTrue(store.pending(clock.instant()).isEmpty());
      PaymentWorkflowMysqlTest.race(5,()->{workflow.confirmManual(order.id(),"reviewer",true);return null;});
      assertEquals("reviewer",store.find(order.id()).orElseThrow().confirmedBy());assertEquals("API",store.accountType("api-owner"));
      fulfillment.failCredit.set(true);workflow.processPending();assertFalse(store.find(order.id()).orElseThrow().fulfilled());
      clock.now=clock.now.plusSeconds(60);
      new PaymentWorkflow(store,null,fulfillment,clock).processPending();
      assertTrue(store.find(order.id()).orElseThrow().fulfilled());assertEquals(Set.of(order.id()),fulfillment.credits);
      workflow.confirmManual(order.id(),"another-reviewer",false);workflow.processPending();
      assertEquals("reviewer",store.find(order.id()).orElseThrow().confirmedBy());assertEquals(1,fulfillment.credits.size());
      assertThrows(IllegalStateException.class,()->workflow.refund(order.id(),"api-owner"));
      assertThrows(IllegalStateException.class,()->store.confirm(new PaymentProvider.VerifiedPayment(order.id(),"provider",order.amount4(),"CNY",clock.instant()),clock.instant()));
    });
  }
  @Test void incompatibleIdentityAndInvalidFactsDoNotCreateOrders()throws Exception{
    RechargeAccountLoopMysqlTest.withStore((store,clock)->{
      var workflow=new PaymentWorkflow(store,null,new PaymentWorkflowMysqlTest.Fulfillment(),clock);
      var personal=store.manual(new PaymentWorkflow.ManualRecharge("personal","personal",new BigDecimal("50"),RechargeType.PERSONAL,null,"receipt","admin"),BigDecimal.TEN,clock.instant());
      store.confirmManual(personal.id(),"admin",clock.instant());
      assertThrows(IllegalStateException.class,()->workflow.corporate(request("wrong-type","personal")));
      assertThrows(IllegalArgumentException.class,()->workflow.corporate(new PaymentWorkflow.CorporateRecharge("low","api",new BigDecimal("1"),"",proof())));
      assertThrows(IllegalArgumentException.class,()->workflow.corporate(new PaymentWorkflow.CorporateRecharge("remark","api",new BigDecimal("5000"),"x".repeat(201),proof())));
      assertEquals(0,store.corporateHistory(0,20).total());
      var rejected=workflow.corporate(request("reject","api"));workflow.rejectManual(rejected.id(),"admin");
      assertThrows(IllegalStateException.class,()->workflow.confirmManual(rejected.id(),"admin"));assertNull(store.accountType("api"));
    });
  }
}
