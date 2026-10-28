package com.xzwl.cloud.payment;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.payment.application.workflow.*;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="PAYMENT_LOCAL_RECHARGE_MINIMUM",matches="0.10")
class LocalRechargeMinimumMysqlTest {
  @Test void localMinimumMatchesOptionsValidationAndStoredPendingOrders()throws Exception {
    RechargeAccountLoopMysqlTest.withStore((store,clock)->{
      var workflow=new PaymentWorkflow(store,new PaymentWorkflowMysqlTest.Provider(),new PaymentWorkflowMysqlTest.Fulfillment(),clock);
      for(var type:RechargeType.values()){
        String owner="local-minimum-"+type,enterprise=type==RechargeType.ENTERPRISE?"fixture enterprise":null;
        for(boolean first:new boolean[]{true,false}){
          assertEquals("0.1000",RechargeRules.rule(type,first).minimumAmount4());
          assertEquals("0.1000",RechargeRules.rule(type,first).presetAmounts4().getFirst());
          assertTrue(RechargeRules.allowed(type.name(),type,new BigDecimal("0.10"),first));
          assertFalse(RechargeRules.allowed(type.name(),type,new BigDecimal("0.09"),first));
        }
        assertEquals("0.1000",workflow.options(owner).rules().stream().filter(r->r.accountType().equals(type.name())).findFirst().orElseThrow().minimumAmount4());
        assertThrows(IllegalArgumentException.class,()->workflow.create(new PaymentWorkflow.NewRecharge("low-"+type,owner,new BigDecimal("0.09"),type,enterprise)));
        assertThrows(IllegalArgumentException.class,()->workflow.create(new PaymentWorkflow.NewRecharge("fraction-"+type,owner,new BigDecimal("0.101"),type,enterprise)));
        var order=workflow.create(new PaymentWorkflow.NewRecharge("valid-"+type,owner,new BigDecimal("0.10"),type,enterprise));
        assertEquals(new BigDecimal("0.1000"),store.find(order.id()).orElseThrow().amount4());
        assertEquals("PENDING",order.status());assertFalse(order.fulfilled());
        assertEquals(order.id(),workflow.create(new PaymentWorkflow.NewRecharge("valid-"+type,owner,new BigDecimal("0.10"),type,enterprise)).id());
      }
    });
  }
}
