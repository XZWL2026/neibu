package com.xzwl.cloud.access.application.key;
import com.xzwl.cloud.access.domain.key.AccountType;
public record AccessKey(String id,String ownerId,AccountType accountType,String kind,String secret,boolean enabled,String firstRechargeOrderId) {
  @Override public String toString(){return "AccessKey[id="+id+",ownerId="+ownerId+",secret=redacted]";}
}
