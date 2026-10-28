package com.xzwl.cloud.payment;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;
import com.xzwl.cloud.payment.application.workflow.*;
import com.xzwl.cloud.payment.infrastructure.workflow.*;
import com.xzwl.cloud.payment.infrastructure.shouqianba.*;
import org.springframework.beans.factory.ObjectProvider;
import com.xzwl.cloud.payment.infrastructure.identity.HttpIdentityClient;
import com.xzwl.cloud.payment.interfaces.http.PaymentSecurityFilter;
import java.time.Clock;
import java.util.*;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Configuration(proxyBeanMethods=false)
@MapperScan("com.xzwl.cloud.payment.infrastructure.workflow")
@EnableScheduling
public class PaymentConfiguration {
  @Bean @ConditionalOnMissingBean(SqbConfig.class)
  @ConditionalOnProperty(name="payment.provider",havingValue="shouqianba")
  SqbConfig sqbConfig(org.springframework.core.env.Environment environment){
    var config=new HashMap<String,String>();
    for(String key:List.of("API_DOMAIN","MERCHANT_SN","APP_ID","DEVICE_ID","VENDOR_SN","VENDOR_KEY","CODE",
        "NOTIFY_URL","PAYWAY","TERMINAL_SN","TERMINAL_KEY","TERMINAL_KEY_DATE","PUBLIC_KEY","DATA_ENCRYPTION_KEY")) {
      String value=environment.getProperty("payment.sqb."+key.toLowerCase(Locale.ROOT).replace('_','-'),
          environment.getProperty("SQB_"+key));
      if(value!=null) config.put("SQB_"+key,value);
    }
    return SqbConfig.fromEnvironment(config,false);
  }
  @Bean @ConditionalOnBean(SqbConfig.class)
  ShouqianbaStateStore shouqianbaStateStore(PaymentMappers.ProviderState mapper,PlatformTransactionManager tx,SqbConfig config){
    return new ShouqianbaStateStore(mapper,new TransactionTemplate(tx),config.cipher());
  }
  @Bean PaymentWorkflowStore paymentWorkflowStore(PaymentMappers.Orders orders,PaymentMappers.Accounts accounts,
      PaymentMappers.Requests requests,PaymentMappers.Work work,PlatformTransactionManager tx,ObjectProvider<SqbConfig> config,RechargeFulfillmentPort fulfillment,org.springframework.core.env.Environment environment){
    var providerConfig=config.getIfAvailable();
    String key=environment.getProperty("payment.sqb.data-encryption-key",environment.getProperty("SQB_DATA_ENCRYPTION_KEY"));
    // Historical encrypted orders remain readable when the payment channel is disabled.
    var cipher=providerConfig!=null?providerConfig.cipher():key==null?null:new PaymentDataCipher(key);
    return new MybatisPaymentWorkflowStore(orders,accounts,requests,work,new TransactionTemplate(tx),cipher,fulfillment::existingAccountType);
  }
  @Bean @ConditionalOnMissingBean PaymentProvider paymentProvider(ObjectProvider<SqbConfig> config,ObjectProvider<ShouqianbaStateStore> store){
    if(config.getIfAvailable()!=null)return new ShouqianbaPaymentProvider(config.getObject(),store.getObject(),Clock.systemUTC());
    return new PaymentProvider(){
      public Checkout create(Charge charge){throw new Unavailable();}
      public VerifiedPayment verify(byte[] raw,Map<String,List<String>> headers){throw new Unavailable();}
      public String refund(Refund refund){throw new Unavailable();}
    };
  }
  @Bean @ConditionalOnMissingBean(RechargeFulfillmentPort.class) RechargeFulfillmentPort rechargeFulfillment(@Qualifier("internalRestClientBuilder") RestClient.Builder builder,
      @Value("${payment.billing-service-url:http://127.0.0.1:8214}") String billing,
      @Value("${payment.access-service-url:http://127.0.0.1:8212}") String access,
      @Value("${payment.promotion-service-url:http://127.0.0.1:8213}") String promotion,
      @Value("${abp.internal-token}") String token){return new HttpRechargeFulfillment(builder,billing,access,promotion,token);}
  @Bean PaymentWorkflow paymentWorkflow(PaymentWorkflowStore store,PaymentProvider provider,RechargeFulfillmentPort fulfillment){
    return new PaymentWorkflow(store,provider,fulfillment,Clock.systemUTC());
  }
  @Bean HttpIdentityClient identityClient(@Qualifier("internalRestClientBuilder") RestClient.Builder builder, @Value("${payment.iam-service-url:http://127.0.0.1:8210}") String iam,
      @Value("${payment.user-service-url:http://127.0.0.1:8211}") String users,
      @Value("${payment.user-internal-token}") String token){return new HttpIdentityClient(builder,iam,users,token);}
  @Bean PaymentSecurityFilter paymentSecurityFilter(HttpIdentityClient identity,@Value("${abp.internal-token}") String token){return new PaymentSecurityFilter(identity,token);}
  @Bean @ConditionalOnProperty(name="payment.worker-enabled",havingValue="true",matchIfMissing=true)
  Worker paymentWorker(PaymentWorkflow workflow){return new Worker(workflow);}
  public static class Worker{
    private final PaymentWorkflow workflow;public Worker(PaymentWorkflow workflow){this.workflow=workflow;}
    @Scheduled(fixedDelayString="${payment.worker-delay-ms:5000}") public void run(){workflow.processPending();}
    @Scheduled(fixedDelayString="${payment.worker-delay-ms:5000}") public void promotions(){workflow.processPromotions();}
  }
}
