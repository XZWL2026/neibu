package com.xzwl.cloud.billing.application.ledger;
public interface KeyOwnershipPort {
  Key find(String keyId);
  record Key(String keyId,String ownerId,boolean enabled){}
}
