package com.xzwl.cloud.payment;

import com.xzwl.cloud.payment.application.workflow.PaymentWorkflow;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import com.xzwl.cloud.payment.interfaces.http.PaymentController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import com.xzwl.cloud.payment.application.workflow.*;

class PortalPaymentRoleTest {
  @Test void apiCreatesOnlyItsOwnApiOrder() {
    var clock=new PaymentWorkflowMysqlTest.MutableClock();
    var created=new java.util.concurrent.atomic.AtomicReference<PaymentWorkflow.NewRecharge>();
    var order=new RechargeOrder("order","real-owner",new java.math.BigDecimal("5000.0000"),java.math.BigDecimal.TEN,
        RechargeType.API,null,clock.instant(),clock.instant().plusSeconds(600),"PENDING",null,null,"provider-order",
        "https://pay.example.test/order",false,null,null,"ONLINE",null,null,null,null,null,null,null);
    var store=(PaymentWorkflowStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PaymentWorkflowStore.class},(p,m,a)->{
      if(m.getName().equals("create")){created.set((PaymentWorkflow.NewRecharge)a[0]);return order;}
      if(m.getName().equals("find"))return java.util.Optional.of(order);
      throw new AssertionError(m.getName());
    });
    var controller=new PaymentController(new PaymentWorkflow(store,new PaymentWorkflowMysqlTest.Provider(),new PaymentWorkflowMysqlTest.Fulfillment(),clock),id->true);
    var api=new MockHttpServletRequest();api.setUserPrincipal(()->"real-owner");api.addUserRole("API_USER");
    var view=controller.create(new PaymentController.NewRecharge("request","5000.0000",RechargeType.API,null),api);
    assertEquals("real-owner",created.get().userId());assertEquals(RechargeType.API,created.get().rechargeType());
    assertEquals("request",created.get().requestId());assertEquals("real-owner",view.userId());assertFalse(view.fulfilled());
    var other=new MockHttpServletRequest();other.setUserPrincipal(()->"other-owner");other.addUserRole("API_USER");
    assertThrows(PaymentWorkflow.Forbidden.class,()->controller.get("order",other));
    for(String role:new String[]{"ADMIN","NORMAL_ADMIN","PROMOTER"}){
      var denied=new MockHttpServletRequest();denied.setUserPrincipal(()->"real-owner");denied.addUserRole(role);
      assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.create(new PaymentController.NewRecharge("request","5000.0000",RechargeType.API,null),denied)).getStatusCode().value());
    }
  }
  @Test void apiReadsOnlyItsAccountAndCannotRequestPersonalOrAdministrativeMoneyOperations() {
    var store=(PaymentWorkflowStore)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PaymentWorkflowStore.class},(p,m,a)->{
      assertEquals("real-owner",a[0]);
      return switch(m.getName()){case "accountType"->"API";case "firstRecharge"->false;case "history"->List.of();default->throw new AssertionError(m.getName());};
    });
    var fulfillment=(RechargeFulfillmentPort)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{RechargeFulfillmentPort.class},(p,m,a)->{
      if(m.getName().equals("currentRate"))return new java.math.BigDecimal("10.0000");
      throw new AssertionError(m.getName());
    });
    var workflow=new PaymentWorkflow(store,null,fulfillment,java.time.Clock.systemUTC());
    var controller=new PaymentController(workflow, id->true);
    var api=new MockHttpServletRequest();api.setUserPrincipal(()->"real-owner");api.addUserRole("API_USER");
    assertEquals("real-owner",controller.options(api).userId());
    assertTrue(controller.history(api).isEmpty());
    assertThrows(ResponseStatusException.class,()->controller.create(new PaymentController.NewRecharge("request","1.0000",RechargeType.PERSONAL,null),api));
    assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.create(new PaymentController.NewRecharge("request","5000.0000",RechargeType.ENTERPRISE,"Company"),api)).getStatusCode().value());
    assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.create(new PaymentController.NewRecharge("request","5000.0000",null,null),api)).getStatusCode().value());
    assertThrows(ResponseStatusException.class,()->controller.adminHistory(api));
    assertThrows(ResponseStatusException.class,()->controller.refund("someone-elses-order",api));
    var promoter=new MockHttpServletRequest();promoter.setUserPrincipal(()->"real-owner");promoter.addUserRole("PROMOTER");
    assertThrows(ResponseStatusException.class,()->controller.options(promoter));
    assertThrows(ResponseStatusException.class,()->controller.get("someone-elses-order",promoter));
  }
}
