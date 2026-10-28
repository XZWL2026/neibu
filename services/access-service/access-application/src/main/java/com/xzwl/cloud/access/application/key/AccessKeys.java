package com.xzwl.cloud.access.application.key;
public final class AccessKeys {
  private final AccessKeyStore store;private final UserStatusPort users;
  public AccessKeys(AccessKeyStore store,UserStatusPort users){this.store=store;this.users=users;}
  public AccessKey authorize(String secret,String capability){
    if(!"API".equals(capability)&&!"MEDIA".equals(capability))throw new Denied();
    var key=store.findBySecret(secret).orElseThrow(Denied::new);
    if(!key.enabled()||!key.kind().equals(capability)||!users.enabled(key.ownerId()))throw new Denied();
    return key;
  }
  public AccessKey ownerKey(String ownerId){
    if(!users.enabled(ownerId))throw new Denied();
    return store.findByOwner(ownerId).orElseThrow(()->new IllegalStateException("first successful recharge required"));
  }
  public AccessKey keyStatus(String keyId){
    var key=store.findById(keyId).orElseThrow(()->new IllegalArgumentException("unknown key"));
    return new AccessKey(key.id(),key.ownerId(),key.accountType(),key.kind(),key.secret(),key.enabled()&&users.enabled(key.ownerId()),key.firstRechargeOrderId());
  }
  public static final class Denied extends RuntimeException {}
}
