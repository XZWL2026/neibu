package com.xzwl.cloud.payment.infrastructure.shouqianba;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.payment.infrastructure.workflow.PaymentMappers;
import java.util.*;
import java.util.function.Function;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Row locking works across application instances; only encrypted allowlisted fields are persisted. */
public final class ShouqianbaStateStore {
  private final PaymentMappers.ProviderState mapper;private final TransactionTemplate tx;
  private final PaymentDataCipher cipher;private final ObjectMapper json=new ObjectMapper();
  public ShouqianbaStateStore(PaymentMappers.ProviderState mapper,TransactionTemplate tx,PaymentDataCipher cipher){
    this.mapper=mapper;this.tx=tx;this.cipher=cipher;tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }
  public <T>T locked(String id,Function<Map<String,String>,T> action){
    return tx.execute(status->{
      mapper.ensure(id);String payload=mapper.lock(id);Map<String,String> values=read(id,payload);
      T result=action.apply(values);
      try{mapper.save(id,cipher.encrypt(id,json.writeValueAsString(values)));}
      catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("payment state cannot be saved");}
      return result;
    });
  }
  private Map<String,String> read(String id,String payload){
    if(payload==null)return new HashMap<>();
    try{return json.readValue(cipher.decrypt(id,payload),new TypeReference<HashMap<String,String>>(){});}
    catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalStateException("payment state cannot be read");}
  }
}
