package com.xzwl.cloud.access.infrastructure.key;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xzwl.cloud.access.application.key.*;
import com.xzwl.cloud.access.domain.key.AccountType;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
public final class MybatisAccessKeyStore implements AccessKeyStore {
  private final AccessKeyMapper mapper;private final KeyCipher cipher;private final TransactionTemplate tx;
  public MybatisAccessKeyStore(AccessKeyMapper mapper,KeyCipher cipher,TransactionTemplate tx){this.mapper=mapper;this.cipher=cipher;this.tx=tx;}
  public AccessKey ensurePrimary(String owner,AccountType type,String order){return ensurePrimary(owner,type,order,null);}
  public AccessKey ensurePrimary(String owner,AccountType type,String order,java.time.Instant paidAt){
    id(owner);id(order);if(type==null||type==AccountType.UNSET)throw new IllegalArgumentException("recharge account type required");
    var paidTime=paidAt==null?null:java.math.BigDecimal.valueOf(paidAt.getEpochSecond()).add(java.math.BigDecimal.valueOf(paidAt.getNano(),9)).setScale(9);
    return tx.execute(s->{
      mapper.ensureOwner(owner);mapper.lockOwner(owner);
      String applied=mapper.recharge(order);
      if(applied!=null){
        String facts=owner+"|"+type.name()+"|";
        if(!applied.startsWith(facts)||paidTime!=null&&!applied.equals(facts)&&!applied.equals(facts+paidTime.toPlainString()))throw new IllegalStateException("recharge facts conflict");
        return findByOwner(owner).orElseThrow();
      }
      var prior=findByOwner(owner);
      if(prior.isPresent()){
        if(prior.get().accountType()!=type&&(paidTime==null||type==AccountType.API||prior.get().accountType()==AccountType.API))throw new IllegalStateException("API identity cannot be changed");
        // Old first-recharge deliveries must also remain idempotent after this migration.
        if(!order.equals(prior.get().firstRechargeOrderId())&&paidTime!=null&&mapper.newerRecharge(owner,order,paidTime)==null)mapper.update(null,new UpdateWrapper<AccessKeyRow>().eq("key_id",prior.get().id()).set("account_type",type.name()));
        mapper.recordRecharge(order,owner,type.name(),paidTime);
        if(prior.get().firstRechargeOrderId()==null)mapper.update(null,new UpdateWrapper<AccessKeyRow>().eq("key_id",prior.get().id()).isNull("paid_order_id").set("paid_order_id",order));
        return findByOwner(owner).orElseThrow();
      }
      var secret=cipher.generate(type==AccountType.API);
      var row=new AccessKeyRow();row.keyId=UUID.randomUUID().toString();row.ownerId=owner;row.accountType=type.name();
      row.keyType=type==AccountType.API?"API":"MEDIA";row.secretHash=KeyCipher.digest(secret);
      row.encryptedSecret=cipher.encrypt(owner,secret);row.paidOrderId=order;row.enabled=true;
      mapper.insert(row);mapper.recordRecharge(order,owner,type.name(),paidTime);return convert(row);
    });
  }
  public void manage(String request,String owner,AccountType type,boolean create,String operator){
    id(request);id(owner);id(operator);
    if(type==null||type==AccountType.UNSET)throw new IllegalArgumentException("account type required");
    if(type==AccountType.API&&!create)throw new IllegalArgumentException("API primary key required");
    try{tx.executeWithoutResult(s->{
      mapper.ensureOwner(owner);mapper.lockOwner(owner);
      String facts=owner+"|"+type.name()+"|"+(create?"1":"0")+"|"+operator,prior=mapper.change(request);
      if(prior!=null){if(!prior.equals(facts))throw new IllegalStateException("idempotency facts conflict");return;}
      var existing=findByOwner(owner);
      if(existing.isPresent()){
        var key=existing.get();
        mapper.update(null,new UpdateWrapper<AccessKeyRow>().eq("key_id",key.id()).set("account_type",type.name()).set("key_type",type==AccountType.API?"API":"MEDIA"));
      }else if(create){
        String secret=cipher.generate(type==AccountType.API);var row=new AccessKeyRow();row.keyId=UUID.randomUUID().toString();row.ownerId=owner;
        row.accountType=type.name();row.keyType=type==AccountType.API?"API":"MEDIA";row.secretHash=KeyCipher.digest(secret);row.encryptedSecret=cipher.encrypt(owner,secret);row.enabled=true;
        mapper.insert(row);
      }
      mapper.recordChange(request,owner,type.name(),create,operator);
    });}catch(org.springframework.dao.DuplicateKeyException conflict){throw new IllegalStateException("idempotency facts conflict");}
  }
  public List<DirectoryKey> directoryKeys(List<String> owners,String search) {
    if(owners==null||owners.isEmpty()||owners.size()>100||new HashSet<>(owners).size()!=owners.size()||search==null||search.length()>128)
      throw new IllegalArgumentException("invalid directory batch");
    owners.forEach(MybatisAccessKeyStore::id);
    return mapper.selectList(new QueryWrapper<AccessKeyRow>().in("owner_id",owners)).stream().map(row->{
      String secret=cipher.decrypt(row.ownerId,row.encryptedSecret);
      String masked=secret.substring(0,secret.length()-4)+"****";
      boolean matches=!search.isEmpty()&&(row.keyId.contains(search)||masked.contains(search));
      return new DirectoryKey(row.ownerId,row.keyId,row.accountType,row.enabled,masked,matches);
    }).toList();
  }
  public List<AccessKey> list(String owner){
    if(owner!=null)id(owner);
    return mapper.selectList(new QueryWrapper<AccessKeyRow>().eq(owner!=null,"owner_id",owner).orderByAsc("key_id").last("LIMIT 1000")).stream().map(this::convert).toList();
  }
  public Optional<AccessKey> findByOwner(String owner){id(owner);return Optional.ofNullable(mapper.selectOne(new QueryWrapper<AccessKeyRow>().eq("owner_id",owner))).map(this::convert);}
  public Optional<AccessKey> findById(String key){id(key);return Optional.ofNullable(mapper.selectById(key)).map(this::convert);}
  public Optional<AccessKey> findBySecret(String secret){
    if(secret==null||!secret.matches("sk-(?:[a-z0-9]{32}|_[0-9a-f]{64})"))return Optional.empty();
    return Optional.ofNullable(mapper.selectOne(new QueryWrapper<AccessKeyRow>().eq("secret_hash",KeyCipher.digest(secret)))).map(this::convert);
  }
  public void setEnabled(String id,boolean enabled){
    id(id);if(mapper.update(null,new UpdateWrapper<AccessKeyRow>().eq("key_id",id).set("enabled",enabled))!=1)
      throw new IllegalArgumentException("unknown key");
  }
  private AccessKey convert(AccessKeyRow r){return new AccessKey(r.keyId,r.ownerId,AccountType.valueOf(r.accountType),r.keyType,cipher.decrypt(r.ownerId,r.encryptedSecret),r.enabled,r.paidOrderId);}
  private static void id(String value){if(value==null||!value.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("invalid identifier");}
}
