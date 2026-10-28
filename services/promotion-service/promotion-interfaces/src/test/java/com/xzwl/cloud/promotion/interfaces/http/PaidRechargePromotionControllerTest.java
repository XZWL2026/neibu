package com.xzwl.cloud.promotion.interfaces.http;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.xzwl.cloud.promotion.application.production.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PaidRechargePromotionControllerTest {
  @Test void requiresInternalTokenAndValidImmutableFacts() throws Exception {
    var calls=new AtomicInteger();
    var store=(PromotionStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PromotionStore.class},(p,m,a)->{calls.incrementAndGet();return "RESOLVED";});
    var mvc=MockMvcBuilders.standaloneSetup(new PaidRechargePromotionController(new PromotionService(store),"test-token"))
      .setControllerAdvice(new PromotionHttpExceptionHandler()).build();
    String body="""
      {"orderId":"order-1","userId":"user-1","amount4":"100.0000","createdAt":"2026-09-15T00:00:00Z","paidAt":"2026-09-15T00:01:00Z","refundedAt":null}
      """;
    String path="/internal/v1/promotions/paid-recharges";
    for(String token:new String[]{"","wrong"})assertEquals(403,mvc.perform(post(path).header("X-Service-Token",token).header("X-Actor-Role","SERVICE").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    assertEquals(0,calls.get());
    assertEquals(200,mvc.perform(post(path).header("X-Service-Token","test-token").contentType("application/json").content(body)).andReturn().getResponse().getStatus());
    for(String amount:new String[]{"0","-1","100.00001","1e40"})assertEquals(400,mvc.perform(post(path).header("X-Service-Token","test-token").contentType("application/json").content(body.replace("100.0000",amount))).andReturn().getResponse().getStatus());
    assertEquals(1,calls.get());
  }
}
