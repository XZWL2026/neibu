package com.xzwl.cloud.payment.application.workflow;
import com.xzwl.cloud.payment.domain.order.RechargeType;
import java.math.*;
import java.util.*;
public final class RechargeRules {
  private RechargeRules(){}
  public record Rule(String accountType,String minimumAmount4,List<String> presetAmounts4){}
  public static List<Rule> rules(boolean first){return Arrays.stream(RechargeType.values()).map(t->rule(t,first)).toList();}
  public static Rule rule(RechargeType type,boolean first){
    Objects.requireNonNull(type,"account type required");
    var values=switch(type){
      case PERSONAL->first?List.of("50","200","500","1000","10000"):List.of("200","500","1000","10000");
      case ENTERPRISE->first?List.of("1000","2000","5000","10000","50000"):List.of("2000","5000","10000","50000");
      case API->List.of("5000","10000","50000","100000");
    };
    var amounts=values.stream().map(v->v+".0000").toList();
    String localMinimum=System.getenv("PAYMENT_LOCAL_RECHARGE_MINIMUM");
    if(localMinimum!=null&&!localMinimum.isBlank()){
      var minimum=yuan(new BigDecimal(localMinimum));
      amounts=java.util.stream.Stream.concat(java.util.stream.Stream.of(minimum.toPlainString()),
        amounts.stream().filter(value->new BigDecimal(value).compareTo(minimum)>0)).toList();
    }
    return new Rule(type.name(),amounts.getFirst(),amounts);
  }
  public static BigDecimal yuan(BigDecimal value){
    if(value==null||value.signum()<=0||value.compareTo(BigDecimal.TEN.pow(34))>=0)throw new IllegalArgumentException("invalid amount");
    try{return value.setScale(2,RoundingMode.UNNECESSARY).setScale(4);}
    catch(ArithmeticException e){throw new IllegalArgumentException("whole cents required");}
  }
  private static boolean compatible(String current,RechargeType type){
    return current==null||current.equals(type.name())||Set.of("PERSONAL","ENTERPRISE").contains(current)&&type!=RechargeType.API;
  }
  public static boolean allowed(String lockedType,RechargeType type,BigDecimal amount){
    return allowed(lockedType,type,amount,lockedType==null);
  }
  public static boolean allowed(String lockedType,RechargeType type,BigDecimal amount,boolean first){
    return compatible(lockedType,type)&&amount.compareTo(new BigDecimal(rule(type,first).minimumAmount4()))>=0;
  }
  public static void validate(String lockedType,RechargeType type,BigDecimal amount){
    validate(lockedType,type,amount,lockedType==null);
  }
  public static void validateAccountType(String lockedType,RechargeType type){
    if(!compatible(lockedType,type))throw new IllegalStateException("account type locked");
  }
  public static void validate(String lockedType,RechargeType type,BigDecimal amount,boolean first){
    validateAccountType(lockedType,type);
    if(!allowed(lockedType,type,amount,first))throw new IllegalArgumentException("amount below recharge minimum");
  }
}
