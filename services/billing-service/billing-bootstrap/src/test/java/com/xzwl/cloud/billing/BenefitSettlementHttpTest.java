package com.xzwl.cloud.billing;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.billing.application.ledger.BenefitSettlementStore;
import com.xzwl.cloud.billing.interfaces.http.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class BenefitSettlementHttpTest {
  @Test void internalCommandsRequireServiceIdentityAndStringDecimals() throws Exception {
    var calls=new java.util.concurrent.atomic.AtomicInteger();
    BenefitSettlementStore store=new BenefitSettlementStore(){
      public void settle(Settlement c){calls.incrementAndGet();}
      public Receipt reward(Reward c){calls.incrementAndGet();return new Receipt("wallet","5.0000","0.0000","0.0000");}
      public String debtPoints(String owner){return "2.0000";}
      public List<Consumption> pending(Instant t){return List.of();}public void acknowledged(String id){}public void retry(String id,Instant t){}
    };
    String token="test-only-service-token-0000000000000000";
    var mvc=MockMvcBuilders.standaloneSetup(new BenefitSettlementController(store,new ObjectMapper().findAndRegisterModules()))
        .setControllerAdvice(new BillingErrors()).addFilters(new BillingInternalFilter(token)).build();
    String body="{\"eventId\":\"event\",\"kind\":\"GRANT\",\"rewardEntitlementId\":\"reward\",\"beneficiaryUserId\":\"user\",\"sourceSettlementId\":\"task\",\"sourceAllocationId\":\"allocation\",\"rewardPoints4\":\"5.0000\",\"occurredAt\":\"2026-09-15T00:00:00Z\"}";
    String path="/internal/v1/billing/referral-rewards";
    mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    mvc.perform(post(path).header("X-Service-Token",token).contentType("application/json").content(body.replace("\"5.0000\"","5"))).andExpect(status().isBadRequest());
    mvc.perform(post(path).header("X-Service-Token",token).contentType("application/json").content(body)).andExpect(status().isOk())
        .andExpect(r->assertTrue(new ObjectMapper().readTree(r.getResponse().getContentAsString()).get("creditedPoints4").isTextual()));
    var get=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/v1/billing/referral-rewards/disabled-owner/balance");
    mvc.perform(get).andExpect(status().isUnauthorized());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/internal/v1/billing/referral-rewards/disabled-owner/balance").header("X-Service-Token",token))
        .andExpect(status().isOk()).andExpect(r->assertEquals("2.0000",new ObjectMapper().readTree(r.getResponse().getContentAsString()).get("debtPoints4").asText()));
    assertEquals(1,calls.get());
  }
}
