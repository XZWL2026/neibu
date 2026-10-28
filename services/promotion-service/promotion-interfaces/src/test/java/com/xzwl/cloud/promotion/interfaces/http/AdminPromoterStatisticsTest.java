package com.xzwl.cloud.promotion.interfaces.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.promotion.application.production.PromotionService;
import com.xzwl.cloud.promotion.application.production.PromotionStore;
import com.xzwl.cloud.promotion.application.production.PromotionStore.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminPromoterStatisticsTest {
  @Test void adminReadsDisabledAgentStatisticsWithoutOpeningPortalAccess() throws Exception {
    var agent=new PromoterView("disabled-agent","REALCODE",PromoterStatus.DISABLED,Instant.EPOCH,Instant.EPOCH);
    var amount=new BigDecimal("9007199254740993.1234");
    var commission=new CommissionView(agent.userId(),amount,amount,amount,amount,amount,1,amount);
    PromotionStore store=(PromotionStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PromotionStore.class},(proxy,method,args)->{
      switch(method.getName()){
        case "listPromoters": return new Page<>(List.of(agent),(int)args[1],(int)args[2],1);
        case "getPromoter": assertEquals(agent.userId(),args[0]); return agent;
        case "portalOverview": assertEquals(agent.userId(),args[0]); return new PortalOverview(agent,commission,5,"123.4567","80.0123","2.0000",3,2,"8.0012","12.3456");
        case "portalDownlines": case "portalRecharges": case "portalConsumptions": assertEquals(agent.userId(),args[0]); return new Page<>(List.of(),(int)args[1],(int)args[2],0);
        case "portalEligibility": return new PortalEligibility(agent.userId(),false);
        default: throw new AssertionError(method.getName());
      }
    });
    var service=new PromotionService(store);
    var mvc=MockMvcBuilders.standaloneSetup(new PromotionController(service)).setControllerAdvice(new PromotionHttpExceptionHandler()).build();
    String root="/api/v1/admin/promotions/promoters";
    for(String endpoint:List.of(root+"/overview",root+"/disabled-agent/downlines",root+"/disabled-agent/recharges",root+"/disabled-agent/consumptions")){
      assertEquals(401,mvc.perform(get(endpoint)).andReturn().getResponse().getStatus());
      for(String role:List.of("USER","PROMOTER","API_USER"))assertEquals(403,mvc.perform(get(endpoint).with(r->{r.setUserPrincipal(()->"other");r.addUserRole(role);return r;})).andReturn().getResponse().getStatus());
      for(String role:List.of("ADMIN","NORMAL_ADMIN")){
        assertEquals(200,mvc.perform(get(endpoint).with(r->{r.setUserPrincipal(()->"admin");r.addUserRole(role);return r;})).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(get(endpoint+"?page=-1").with(r->{r.setUserPrincipal(()->"admin");r.addUserRole(role);return r;})).andReturn().getResponse().getStatus());
      }
    }
    var response=mvc.perform(get(root+"/overview").with(r->{r.setUserPrincipal(()->"admin");r.addUserRole("ADMIN");return r;})).andReturn().getResponse();
    var item=new ObjectMapper().readTree(response.getContentAsString()).path("items").get(0);
    assertEquals(3,item.path("levelOneCount").asInt());
    assertEquals(2,item.path("levelTwoCount").asInt());
    assertEquals("9007199254740993.1234",item.path("commission").path("available").asText());
    assertEquals("80.0123",item.path("settledPaidAmount4").asText());
    assertThrows(IllegalStateException.class,()->service.portalOverview(agent.userId()));
  }
}
