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

class BenefitSettingsControllerTest {
  @Test void onlyPrimaryCanReadWriteAndServerOwnsActorAndTime() throws Exception {
    var saved=new AtomicReference<PromotionStore.ChangeBenefitRate>();
    var store=(PromotionStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PromotionStore.class},(p,m,a)->{
      if(m.getName().equals("changeBenefitRate"))saved.set((PromotionStore.ChangeBenefitRate)a[0]);
      if(m.getName().equals("benefitSettingsHistory"))return List.of();
      return new PromotionStore.BenefitSettings(1,"1970-01-01T00:00:00Z","system","初始化",new PromotionStore.BenefitGlobal("0.1","0.08","0.03"),List.of());
    });
    var mvc=MockMvcBuilders.standaloneSetup(new BenefitSettingsController(new PromotionService(store)))
        .setControllerAdvice(new PromotionHttpExceptionHandler()).build();
    String path="/api/v1/admin/promotions/benefit-settings";
    String body="""
      {"commandId":"save","expectedVersion":1,"field":"defaultLevel1CashRate","rate":"0.08","reason":"调整","adminId":"forged","effectiveAt":"2000-01-01T00:00:00Z"}
      """;
    for(String suffix:new String[]{"","/history"}) {
      assertEquals(401,mvc.perform(get(path+suffix)).andReturn().getResponse().getStatus());
      assertEquals(403,mvc.perform(as(get(path+suffix),false)).andReturn().getResponse().getStatus());
      assertEquals(200,mvc.perform(as(get(path+suffix),true)).andReturn().getResponse().getStatus());
    }
    assertEquals(403,mvc.perform(as(post(path).contentType("application/json").content(body),false)).andReturn().getResponse().getStatus());
    assertNull(saved.get());
    assertEquals(200,mvc.perform(as(post(path).contentType("application/json").content(body),true)).andReturn().getResponse().getStatus());
    assertEquals("actual-admin",saved.get().adminId());
    assertFalse(java.util.Arrays.stream(PromotionStore.ChangeBenefitRate.class.getRecordComponents()).anyMatch(c->c.getName().equals("effectiveAt")));
    String customerBody=body.replace("\"field\":\"defaultLevel1CashRate\"","\"promoterId\":\"customer\",\"field\":\"invitationPointsRate\"");
    assertEquals(200,mvc.perform(as(post(path).contentType("application/json").content(customerBody),true)).andReturn().getResponse().getStatus());
    assertEquals("customer",saved.get().promoterId());
    assertEquals("invitationPointsRate",saved.get().field());
    assertEquals(200,mvc.perform(as(post(path).contentType("application/json").content(customerBody.replace("\"0.08\"","null")),true)).andReturn().getResponse().getStatus());
    assertNull(saved.get().rate());
    for(String invalid:new String[]{"-0.1","1.1","0.1234567","NaN","1e-2"})
      assertEquals(400,mvc.perform(as(post(path).contentType("application/json").content(body.replace("0.08",invalid)),true)).andReturn().getResponse().getStatus());
  }
  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,boolean primary) {
    return request.principal(()->"actual-admin").with(r->{r.addUserRole("ADMIN");if(primary)r.addUserRole("PRIMARY_ADMIN");return r;});
  }
}
