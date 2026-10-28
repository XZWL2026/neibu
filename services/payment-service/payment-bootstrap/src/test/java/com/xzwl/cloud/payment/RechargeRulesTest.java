package com.xzwl.cloud.payment;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.payment.application.workflow.RechargeRules;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
class RechargeRulesTest {
  @Test void exactPersonalEnterpriseAndApiPresets(){
    assertEquals(List.of("50.0000","200.0000","500.0000","1000.0000","10000.0000"),RechargeRules.rule(RechargeType.PERSONAL,true).presetAmounts4());
    assertEquals(List.of("200.0000","500.0000","1000.0000","10000.0000"),RechargeRules.rule(RechargeType.PERSONAL,false).presetAmounts4());
    assertEquals(List.of("1000.0000","2000.0000","5000.0000","10000.0000","50000.0000"),RechargeRules.rule(RechargeType.ENTERPRISE,true).presetAmounts4());
    assertEquals(List.of("2000.0000","5000.0000","10000.0000","50000.0000"),RechargeRules.rule(RechargeType.ENTERPRISE,false).presetAmounts4());
    for(boolean first:List.of(true,false))assertEquals(List.of("5000.0000","10000.0000","50000.0000","100000.0000"),RechargeRules.rule(RechargeType.API,first).presetAmounts4());
  }
  @Test void allThresholdsAreInclusiveAndNoProductMaximum(){
    for(var type:RechargeType.values())for(boolean first:List.of(true,false)){
      var minimum=new BigDecimal(RechargeRules.rule(type,first).minimumAmount4());String locked=first?null:type.name();
      assertFalse(RechargeRules.allowed(locked,type,minimum.subtract(new BigDecimal("0.01"))));
      assertTrue(RechargeRules.allowed(locked,type,minimum));
      assertTrue(RechargeRules.allowed(locked,type,new BigDecimal("1000000000000.00")));
    }
  }
  @Test void centsAreExactAndStorageLimitRemains(){
    assertEquals(new BigDecimal("50.0100"),RechargeRules.yuan(new BigDecimal("50.010000")));
    for(String bad:List.of("0","-1","50.001","1E34"))assertThrows(IllegalArgumentException.class,()->RechargeRules.yuan(new BigDecimal(bad)));
  }
}
