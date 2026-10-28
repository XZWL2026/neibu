package com.xzwl.cloud.payment;

import com.xzwl.cloud.payment.infrastructure.shouqianba.*;
import java.security.KeyPairGenerator;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class SqbConfigurationTest {
  MockEnvironment env;
  @BeforeEach void setup() throws Exception {
    var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
    env=new MockEnvironment().withProperty("SQB_API_DOMAIN","https://api.example.test")
        .withProperty("SQB_MERCHANT_SN","merchant").withProperty("SQB_APP_ID","app").withProperty("SQB_DEVICE_ID","device")
        .withProperty("SQB_NOTIFY_URL","https://local.example.test/callbacks/payment").withProperty("SQB_PAYWAY","3")
        .withProperty("SQB_PUBLIC_KEY",Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded()))
        .withProperty("SQB_DATA_ENCRYPTION_KEY",Base64.getEncoder().encodeToString(new byte[32]));
  }
  SqbConfig bean() {
    var beans=new DefaultListableBeanFactory();beans.registerSingleton("configuration",new PaymentConfiguration());
    beans.registerSingleton("environment",env);
    var definition=new RootBeanDefinition();definition.setFactoryBeanName("configuration");definition.setFactoryMethodName("sqbConfig");
    definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("sqb",definition);
    return beans.getBean(SqbConfig.class);
  }
  @Test void readsYamlPropertiesWhileKeepingCipherCompatibleAndConfigFrozen() {
    var yaml=new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
    yaml.setResources(new org.springframework.core.io.ByteArrayResource("payment:\n  sqb:\n    notify-url: https://online.example.test/callbacks/payment\n    payway: '2'\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    env.getPropertySources().addFirst(new org.springframework.core.env.PropertiesPropertySource("nacos-yaml",yaml.getObject()));
    var config=bean();
    assertEquals("https://online.example.test/callbacks/payment",ReflectionTestUtils.getField(config,"notifyUrl"));
    assertEquals("2",ReflectionTestUtils.getField(config,"payway"));
    String ciphertext=new PaymentDataCipher(env.getRequiredProperty("SQB_DATA_ENCRYPTION_KEY")).encrypt("order","existing-terminal-state");
    assertEquals("existing-terminal-state",config.cipher().decrypt("order",ciphertext));
    env.setProperty("SQB_DEVICE_ID","later-device");assertEquals("device",ReflectionTestUtils.getField(config,"device"));
  }
  @Test void localEnvironmentFallbackStillBuildsAndRedactsCredentials() {
    var config=bean();assertEquals("https://local.example.test/callbacks/payment",ReflectionTestUtils.getField(config,"notifyUrl"));
    assertEquals("SqbConfig[redacted]",config.toString());
  }
  @Test void invalidRemoteValueMustNotSilentlyFallBackToValidLocalValue() {
    env.setProperty("payment.sqb.api-domain","http://unsafe.example.test");assertThrows(RuntimeException.class,this::bean);
  }
  @Test void emptyRemoteValueMustNotSilentlyFallBack() {
    env.setProperty("payment.sqb.api-domain","");assertThrows(RuntimeException.class,this::bean);
  }
  @Test void migratingOrdinaryValuesKeepsExistingTerminalScope() {
    var before=bean();
    for(String key:List.of("API_DOMAIN","MERCHANT_SN","APP_ID","DEVICE_ID","NOTIFY_URL","PAYWAY","PUBLIC_KEY")) {
      env.setProperty("payment.sqb."+key.toLowerCase(Locale.ROOT).replace('_','-'),env.getRequiredProperty("SQB_"+key));
      env.setProperty("SQB_"+key,"unused-legacy-value");
    }
    assertEquals(ReflectionTestUtils.getField(before,"scope"),ReflectionTestUtils.getField(bean(),"scope"));
  }
  @Test void missingEncryptionKeyStillFailsClosed() {
    env.setProperty("SQB_DATA_ENCRYPTION_KEY","");assertThrows(RuntimeException.class,this::bean);
  }
  @Test void disabledProviderStillReadsEncryptedHistoryWithoutEnablingPayments() {
    env.setProperty("payment.provider","disabled");
    var beans=new DefaultListableBeanFactory();beans.registerSingleton("configuration",new PaymentConfiguration());beans.registerSingleton("environment",env);
    var history=new java.util.concurrent.atomic.AtomicReference<List<com.xzwl.cloud.payment.infrastructure.workflow.PaymentRows.Order>>();
    var orders=stub(com.xzwl.cloud.payment.infrastructure.workflow.PaymentMappers.Orders.class,(proxy,method,args)->method.getName().equals("selectList")?history.get():null);
    beans.registerSingleton("orders",orders);
    beans.registerSingleton("accounts",stub(com.xzwl.cloud.payment.infrastructure.workflow.PaymentMappers.Accounts.class,(proxy,method,args)->null));
    beans.registerSingleton("requests",stub(com.xzwl.cloud.payment.infrastructure.workflow.PaymentMappers.Requests.class,(proxy,method,args)->null));
    beans.registerSingleton("work",stub(com.xzwl.cloud.payment.infrastructure.workflow.PaymentMappers.Work.class,(proxy,method,args)->null));
    beans.registerSingleton("tx",stub(org.springframework.transaction.PlatformTransactionManager.class,(proxy,method,args)->null));
    beans.registerSingleton("fulfillment",stub(com.xzwl.cloud.payment.application.workflow.RechargeFulfillmentPort.class,(proxy,method,args)->null));
    var definition=new RootBeanDefinition();definition.setFactoryBeanName("configuration");definition.setFactoryMethodName("paymentWorkflowStore");
    definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);beans.registerBeanDefinition("store",definition);
    var row=new com.xzwl.cloud.payment.infrastructure.workflow.PaymentRows.Order();row.id="past-order";row.userId="owner";row.rechargeType="PERSONAL";row.status="EXPIRED";row.source="PROVIDER";row.createdAt=0L;row.expiresAt=1L;row.fulfilled=false;row.amount4=new java.math.BigDecimal("50.0000");row.rateSnapshot4=java.math.BigDecimal.TEN;
    row.checkoutUrl=new PaymentDataCipher(env.getRequiredProperty("SQB_DATA_ENCRYPTION_KEY")).encrypt("checkout:"+row.id,"https://pay.example.test/past");
    history.set(List.of(row));
    var store=beans.getBean(com.xzwl.cloud.payment.application.workflow.PaymentWorkflowStore.class);
    assertEquals("https://pay.example.test/past",store.history("owner").getFirst().checkoutUrl());
    var provider=new PaymentConfiguration().paymentProvider(beans.getBeanProvider(SqbConfig.class),beans.getBeanProvider(ShouqianbaStateStore.class));
    assertThrows(com.xzwl.cloud.payment.application.workflow.PaymentProvider.Unavailable.class,()->provider.create(null));
  }

  private static <T> T stub(Class<T> type,java.lang.reflect.InvocationHandler handler){return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}

}
