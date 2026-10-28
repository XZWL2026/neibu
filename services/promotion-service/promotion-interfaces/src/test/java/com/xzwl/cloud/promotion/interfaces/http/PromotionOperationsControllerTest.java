package com.xzwl.cloud.promotion.interfaces.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.xzwl.cloud.promotion.application.production.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PromotionOperationsControllerTest {
  @Test void primaryOnlyReadsAndMutationUsesServerPrincipal() throws Exception {
    var called=new AtomicReference<Object>();
    var operations=(PromotionOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PromotionOperations.class},(p,m,a)->{
      if(a!=null&&a.length>0)called.set(a[0]);return null;
    });
    var mvc=MockMvcBuilders.standaloneSetup(new PromotionOperationsController(operations,"service-token"))
        .setControllerAdvice(new PromotionHttpExceptionHandler()).build();
    for(String path:List.of("/api/v1/admin/promotions/agent-income?month=2026-09","/api/v1/admin/promotions/agent-income/a/draft?month=2026-09","/api/v1/admin/promotions/agent-income/a/consumptions?month=2026-09","/api/v1/admin/promotions/payouts/pay/adjustments","/api/v1/admin/promotions/payouts","/api/v1/admin/promotions/payouts/pay/account","/api/v1/admin/promotions/withdrawal-report","/api/v1/admin/promotions/benefit-audit","/api/v1/admin/promotions/reconciliation","/api/v1/admin/promotions/operations/referrals/u")) {
      assertEquals(401,mvc.perform(get(path)).andReturn().getResponse().getStatus());
      assertEquals(403,mvc.perform(as(get(path),"NORMAL_ADMIN")).andReturn().getResponse().getStatus());
      assertEquals(403,mvc.perform(as(get(path),"ADMIN")).andReturn().getResponse().getStatus());
      assertEquals(200,mvc.perform(as(get(path),"PRIMARY")).andReturn().getResponse().getStatus());
    }
    String body="""
      {"commandId":"c","withdrawalId":"w","promoterUserId":"u","settlementMonth":"2026-09","amount4":"0.0100","payoutAccountId":"bank","adminId":"forged"}
      """;
    assertEquals(403,mvc.perform(as(post("/api/v1/admin/promotions/payouts").contentType("application/json").content(body),"USER")).andReturn().getResponse().getStatus());
    assertEquals(200,mvc.perform(as(post("/api/v1/admin/promotions/payouts").contentType("application/json").content(body),"PRIMARY")).andReturn().getResponse().getStatus());
    assertEquals("actual-user",((PromotionOperations.CreatePayout)called.get()).adminId());
  }
  @Test void rewardOwnerAndInternalServiceTokenCannotBeForged() throws Exception {
    var called=new AtomicReference<Object>();
    var operations=(PromotionOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PromotionOperations.class},(p,m,a)->{if(a!=null&&a.length>0)called.set(a[0]);return null;});
    var mvc=MockMvcBuilders.standaloneSetup(new PromotionOperationsController(operations,"service-token")).build();
    assertEquals(200,mvc.perform(as(get("/api/v1/promotions/rewards?userId=forged"),"USER")).andReturn().getResponse().getStatus());
    assertEquals("actual-user",called.get());
    String body="""
      {"eventId":"e","kind":"SETTLED","settlementId":"t","allocationId":"a","fundingLotId":"l","orderId":"o","walletId":"w","walletOwnerUserId":"u","originalPaidAmount4":"100.0000","originalPoints4":"1000.0000","successfulPoints4":"1.0000","reversedPoints4":"0.0000","occurredAt":"2026-09-15T00:00:00Z"}
      """;
    String path="/internal/v1/promotions/consumption-settlements";
    assertEquals(403,mvc.perform(post(path).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    assertEquals(200,mvc.perform(post(path).header("X-Service-Token","service-token").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    assertEquals("1.0000",((PromotionOperations.ConsumptionSettlement)called.get()).successfulPoints4());
  }
  @Test void legacyDecisionCannotBypassPrimaryOrAuditedOperation() throws Exception {
    var called=new AtomicReference<PromotionOperations.DecidePayout>();
    var operations=(PromotionOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PromotionOperations.class},(p,m,a)->{if(m.getName().equals("decidePayout"))called.set((PromotionOperations.DecidePayout)a[0]);return null;});
    var store=(PromotionStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PromotionStore.class},(p,m,a)->null);
    var controller=new PromotionController(new PromotionService(store));controller.setOperations(operations);
    var mvc=MockMvcBuilders.standaloneSetup(controller).build();
    String path="/api/v1/admin/promotions/withdrawals/payout/decision";
    String body="{\"commandId\":\"decision\",\"approved\":true,\"adminId\":\"forged\",\"decidedAt\":\"2000-01-01T00:00:00Z\"}";
    assertEquals(403,mvc.perform(as(post(path).contentType("application/json").content(body),"ADMIN")).andReturn().getResponse().getStatus());
    assertNull(called.get());
    assertEquals(200,mvc.perform(as(post(path).contentType("application/json").content(body),"PRIMARY")).andReturn().getResponse().getStatus());
    assertEquals("actual-user",called.get().adminId());
  }
  @Test void allAdjustmentRecordsArePrimaryAdminOnly() throws Exception {
    var args=new AtomicReference<List<Object>>();
    var operations=(PromotionOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PromotionOperations.class},(p,m,a)->{if(m.getName().equals("referralAdjustments"))args.set(java.util.Arrays.asList(a));return null;});
    var mvc=MockMvcBuilders.standaloneSetup(new PromotionOperationsController(operations,"service-token")).build();
    String path="/api/v1/admin/promotions/operations/referral-adjustments?userId=client&page=2&size=10";
    for(String role:List.of("USER","NORMAL_ADMIN","ADMIN"))assertEquals(403,mvc.perform(as(get(path),role)).andReturn().getResponse().getStatus());
    assertNull(args.get());assertEquals(200,mvc.perform(as(get(path),"PRIMARY")).andReturn().getResponse().getStatus());assertEquals(List.of("client",2,10),args.get());
  }
  @Test void incomeMutationProtectsPrimaryIdentityAndBothDecisionSnapshots() throws Exception {
    var called=new AtomicReference<Object>();
    var operations=(PromotionOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PromotionOperations.class},(p,m,a)->{if(a!=null&&a.length>0)called.set(a[0]);return null;});
    var store=(PromotionStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PromotionStore.class},(p,m,a)->null);
    var legacy=new PromotionController(new PromotionService(store));legacy.setOperations(operations);
    var mvc=MockMvcBuilders.standaloneSetup(new PromotionOperationsController(operations,"service-token"),legacy).build();
    String path="/api/v1/admin/promotions/payouts/pay/adjustments";
    String body="{\"commandId\":\"adjust\",\"deltaAmount4\":\"-1.00\",\"reason\":\"核对\",\"expectedVersion\":4,\"adminId\":\"forged\"}";
    for(String role:List.of("USER","NORMAL_ADMIN","ADMIN"))assertEquals(403,mvc.perform(as(post(path).contentType("application/json").content(body),role)).andReturn().getResponse().getStatus());
    assertNull(called.get());assertEquals(200,mvc.perform(as(post(path).contentType("application/json").content(body),"PRIMARY")).andReturn().getResponse().getStatus());
    var adjustment=(PromotionOperations.AdjustIncome)called.get();assertEquals("actual-user",adjustment.adminId());assertEquals(4,adjustment.expectedVersion());assertEquals("-1.00",adjustment.deltaAmount4());
    for(String decision:List.of("payouts/pay","withdrawals/pay")) {
      String json="{\"commandId\":\"approve\",\"approved\":true,\"expectedAdjustmentVersion\":5,\"expectedAmount4\":\"6.0000\"}";
      assertEquals(200,mvc.perform(as(post("/api/v1/admin/promotions/"+decision+"/decision").contentType("application/json").content(json),"PRIMARY")).andReturn().getResponse().getStatus());
      var command=(PromotionOperations.DecidePayout)called.get();assertEquals(5L,command.expectedAdjustmentVersion());assertEquals("6.0000",command.expectedAmount4());assertEquals("actual-user",command.adminId());
    }
  }
  @Test void draftUsesPrimaryPrincipalPathMonthAndConfirmedCreateVersion() throws Exception {
    var called=new AtomicReference<Object>();
    var operations=(PromotionOperations)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PromotionOperations.class},(p,m,a)->{if(a!=null&&a.length>0)called.set(a[0]);return null;});
    var mvc=MockMvcBuilders.standaloneSetup(new PromotionOperationsController(operations,"service-token")).build();
    String path="/api/v1/admin/promotions/agent-income/a/draft?month=2026-09";
    String body="{\"commandId\":\"draft\",\"deltaAmount4\":\"-1.00\",\"reason\":\"核对\",\"expectedVersion\":4,\"adminId\":\"forged\"}";
    assertEquals(401,mvc.perform(post(path).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    for(String role:List.of("USER","NORMAL_ADMIN","ADMIN"))assertEquals(403,mvc.perform(as(post(path).contentType("application/json").content(body),role)).andReturn().getResponse().getStatus());
    assertNull(called.get());assertEquals(200,mvc.perform(as(post(path).contentType("application/json").content(body),"PRIMARY")).andReturn().getResponse().getStatus());
    var command=(PromotionOperations.AdjustIncomeDraft)called.get();assertEquals("actual-user",command.adminId());assertEquals("a",command.promoterUserId());assertEquals("2026-09",command.settlementMonth());assertEquals(4,command.expectedVersion());
    String create="{\"commandId\":\"create\",\"withdrawalId\":\"draft-id\",\"promoterUserId\":\"a\",\"settlementMonth\":\"2026-09\",\"amount4\":\"5.00\",\"payoutAccountId\":\"bank\",\"expectedDraftVersion\":5}";
    assertEquals(200,mvc.perform(as(post("/api/v1/admin/promotions/payouts").contentType("application/json").content(create),"PRIMARY")).andReturn().getResponse().getStatus());
    assertEquals(5L,((PromotionOperations.CreatePayout)called.get()).expectedDraftVersion());
  }
  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,String role) {
    return request.principal(()->"actual-user").with(r->{if(role.equals("PRIMARY")){r.addUserRole("ADMIN");r.addUserRole("PRIMARY_ADMIN");}else r.addUserRole(role);return r;});
  }
}
