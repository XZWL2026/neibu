package com.xzwl.cloud.billing;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.xzwl.cloud.billing.application.ledger.ConsumptionStore;
import com.xzwl.cloud.billing.interfaces.http.PortalConsumptionController;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PortalConsumptionControllerTest {
  @Test void apiReadsOnlySessionOwnerAndNeverAdminDetail() throws Exception {
    var summaryArgs = new java.util.concurrent.atomic.AtomicReference<Object[]>();
    ConsumptionStore store=(ConsumptionStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ConsumptionStore.class},(proxy,method,args)-> {
      if(method.getName().equals("summary")) { summaryArgs.set(args); return new ConsumptionStore.Summary(0,"0.0000","0.0000","0.0000","0.0000","0.0000"); }
      var filter=(ConsumptionStore.Filter)args[0];
      return new ConsumptionStore.Page(List.of(new ConsumptionStore.Row("entry", "task", "key-identifier",filter.userId(),"IMAGE","model","API","2026-09-11T00:00:00Z","4.0000","0.0000","4.0000","0.4000","10.0000","2K",4,0)),1);
    });
    var mvc=MockMvcBuilders.standaloneSetup(new PortalConsumptionController(store),new com.xzwl.cloud.billing.interfaces.http.InternalPortalConsumptionController(store,"token")).build();
    String internal="/internal/v1/billing/owners/owner1/portal-summary";
    assertEquals(403,mvc.perform(get(internal)).andReturn().getResponse().getStatus());
    assertEquals(403,mvc.perform(get(internal).header("X-Service-Token","wrong")).andReturn().getResponse().getStatus());
    var internalResult=mvc.perform(get(internal).header("X-Service-Token","token")).andReturn().getResponse();
    assertEquals(200,internalResult.getStatus());assertTrue(internalResult.getContentAsString().contains("owner1"));
    var summaryResult = mvc.perform(get("/api/v1/billing/portal/summary").param("from", "2026-09-10T16:00:00Z")
        .param("to", "2026-09-11T16:00:00Z").param("userId", "victim").principal(() -> "owner2")
        .with(r -> { r.addUserRole("API_USER"); return r; })).andReturn().getResponse();
    assertEquals(200, summaryResult.getStatus());
    assertArrayEquals(new Object[]{"owner2", java.time.Instant.parse("2026-09-10T16:00:00Z"), java.time.Instant.parse("2026-09-11T16:00:00Z")}, summaryArgs.get());
    String path="/api/v1/billing/portal/consumptions";
    assertEquals(401,mvc.perform(get(path)).andReturn().getResponse().getStatus());
    for(String role:List.of("USER","PROMOTER","ADMIN","NORMAL_ADMIN")) {
      assertEquals(403,mvc.perform(get(path).principal(()->"owner").with(r->{r.addUserRole(role);return r;})).andReturn().getResponse().getStatus());
    }
    for(String owner:List.of("owner1","owner2")) {
      var result=mvc.perform(get(path).param("userId","victim").principal(()->owner).with(r->{r.addUserRole("API_USER");return r;})).andReturn().getResponse();
      assertEquals(200,result.getStatus());assertTrue(result.getContentAsString().contains("\"resolution\":\"2K\""));assertTrue(result.getContentAsString().contains("\"count\":4"));assertTrue(result.getContentAsString().contains("\"durationSeconds\":0"));assertTrue(result.getContentAsString().contains(owner));assertFalse(result.getContentAsString().contains("victim"));
      assertFalse(result.getContentAsString().contains("prompt"));assertFalse(result.getContentAsString().contains("references"));
    }
  }
}
