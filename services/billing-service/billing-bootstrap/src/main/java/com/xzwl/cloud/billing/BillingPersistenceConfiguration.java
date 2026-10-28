package com.xzwl.cloud.billing;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;
import com.xzwl.cloud.billing.application.credit.*;
import com.xzwl.cloud.billing.application.recharge.*;
import com.xzwl.cloud.billing.application.ledger.BillingLedgerStore;
import com.xzwl.cloud.billing.infrastructure.*;
import com.xzwl.cloud.billing.infrastructure.persistence.BillingDatabase;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
@Configuration(proxyBeanMethods=false)
@MapperScan("com.xzwl.cloud.billing.infrastructure.persistence")
public class BillingPersistenceConfiguration {
  @Bean com.xzwl.cloud.billing.application.ledger.BenefitSettlementStore benefitSettlementStore(BillingDatabase db,
      com.xzwl.cloud.billing.infrastructure.identity.HttpKeyOwnershipClient keys) {
    return new MybatisBenefitSettlementStore(db,keys);
  }
  @Bean HttpPromotionSettlementDelivery promotionSettlementDelivery(@Qualifier("internalRestClientBuilder") RestClient.Builder builder, com.xzwl.cloud.billing.application.ledger.BenefitSettlementStore store,
      @org.springframework.beans.factory.annotation.Value("${billing.promotion-service-url:http://127.0.0.1:8213}") String url,
      @org.springframework.beans.factory.annotation.Value("${abp.internal-token}") String token) {
    var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(java.time.Duration.ofSeconds(2));factory.setReadTimeout(java.time.Duration.ofSeconds(5));
    return new HttpPromotionSettlementDelivery(store,builder.clone().baseUrl(url)
        .defaultHeader("X-Service-Token",token).requestFactory(factory).build());
  }
  @Configuration(proxyBeanMethods=false) @org.springframework.scheduling.annotation.EnableScheduling
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="billing.benefits.worker-enabled",havingValue="true",matchIfMissing=true)
  static class BenefitDeliveryScheduling {
    private final HttpPromotionSettlementDelivery delivery;
    BenefitDeliveryScheduling(HttpPromotionSettlementDelivery delivery){this.delivery=delivery;}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${billing.benefits.worker-delay-ms:5000}") void tick(){delivery.tick();}
  }

  @Bean com.xzwl.cloud.billing.application.ledger.ConsumptionStore portalConsumptionStore(org.springframework.jdbc.core.JdbcTemplate jdbc) {
    return new JdbcConsumptionStore(jdbc);
  }
  @Bean com.xzwl.cloud.billing.application.ledger.AdminConsumptionQuery adminConsumptionQuery(@Qualifier("internalRestClientBuilder") RestClient.Builder builder, org.springframework.jdbc.core.JdbcTemplate jdbc,
      @org.springframework.beans.factory.annotation.Value("${billing.media-service-url:http://127.0.0.1:8217}") String media,
      @org.springframework.beans.factory.annotation.Value("${billing.user-service-url:http://127.0.0.1:8211}") String users,
      @org.springframework.beans.factory.annotation.Value("${billing.access-service-url:http://127.0.0.1:8212}") String access,
      @org.springframework.beans.factory.annotation.Value("${abp.internal-token}") String token) {
    var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(java.time.Duration.ofSeconds(2));factory.setReadTimeout(java.time.Duration.ofSeconds(5));
    return new HttpAdminConsumptionQuery(new JdbcConsumptionStore(jdbc),
        builder.clone().baseUrl(media).requestFactory(factory).build(),
        builder.clone().baseUrl(users).requestFactory(factory).build(),
        builder.clone().baseUrl(access).requestFactory(factory).defaultHeader("X-Service-Token",token).build());
  }
  @Bean com.xzwl.cloud.billing.application.ledger.MediaBilling mediaBilling(BillingDatabase db,
      com.xzwl.cloud.billing.infrastructure.identity.HttpKeyOwnershipClient keys,RechargeRateStore rates,BillingLedgerStore ledger,
      com.xzwl.cloud.billing.application.pricing.MediaPriceStore managedPrices,
      com.fasterxml.jackson.databind.ObjectMapper json,
      @org.springframework.beans.factory.annotation.Value("${billing.media-prices:[]}") String configured) throws Exception {
    var catalog=com.xzwl.cloud.billing.domain.pricing.PricingCatalog.empty();
    for(var price:json.readValue(configured,MediaPrice[].class)) {
      var decimal=com.xzwl.cloud.billing.domain.money.Decimal4.of(price.pricePerUnit());
      catalog=catalog.withPrice(new com.xzwl.cloud.billing.domain.pricing.PricingEntry(
          new com.xzwl.cloud.billing.domain.pricing.PricingKey(price.accountType(),price.model(),price.resolution()),decimal));
    }
    return new MybatisMediaBilling(db,keys,catalog,rates,ledger,managedPrices);
  }
  @Bean com.xzwl.cloud.billing.application.pricing.MediaPriceStore mediaPriceStore(org.springframework.jdbc.core.JdbcTemplate jdbc,PlatformTransactionManager tx) {
    return new JdbcMediaPriceStore(jdbc,tx);
  }
  record MediaPrice(String accountType,String model,String resolution,String pricePerUnit) {}

  @Bean com.xzwl.cloud.billing.infrastructure.identity.HttpKeyOwnershipClient keyOwnership(@Qualifier("internalRestClientBuilder") RestClient.Builder builder,
      @org.springframework.beans.factory.annotation.Value("${billing.access-service-url:http://127.0.0.1:8212}") String url,
      @org.springframework.beans.factory.annotation.Value("${abp.internal-token}") String token) {
    return new com.xzwl.cloud.billing.infrastructure.identity.HttpKeyOwnershipClient(builder,url,token);
  }
  @Bean com.xzwl.cloud.billing.interfaces.http.BillingInternalFilter billingInternalFilter(
      @org.springframework.beans.factory.annotation.Value("${abp.internal-token}") String token) {
    return new com.xzwl.cloud.billing.interfaces.http.BillingInternalFilter(token);
  }
  @Bean BillingDatabase billingDatabase(SqlSessionTemplate session,PlatformTransactionManager tx,
      com.xzwl.cloud.billing.infrastructure.persistence.BillingMappers.Wallets wallets,
      com.xzwl.cloud.billing.infrastructure.persistence.BillingMappers.AdminCredits credits,
      com.xzwl.cloud.billing.infrastructure.persistence.BillingMappers.Recharges recharges,
      com.xzwl.cloud.billing.infrastructure.persistence.BillingMappers.Lots lots,
      com.xzwl.cloud.billing.infrastructure.persistence.BillingMappers.Rates rates,
      com.xzwl.cloud.billing.infrastructure.persistence.BillingMappers.Ledger ledger,
      com.xzwl.cloud.billing.infrastructure.persistence.BillingMappers.Allocations allocations) {
    return new BillingDatabase(session,tx);
  }
  @Bean AdminCreditStore adminCreditStore(BillingDatabase db) {return new MybatisAdminCreditStore(db);}
  @Bean AdminCreditHandler adminCreditHandler(AdminCreditStore store) {return new AdminCreditHandler(store);}
  @Bean RechargeCreditStore rechargeCreditStore(BillingDatabase db) {return new MybatisRechargeCreditStore(db);}
  @Bean CreditPaidRechargeHandler creditPaidRechargeHandler(RechargeCreditStore store) {return new CreditPaidRechargeHandler(store);}
  @Bean RechargeRateStore rechargeRateStore(BillingDatabase db) {return new MybatisRechargeRateStore(db);}
  @Bean SetRechargeRateHandler setRechargeRateHandler(RechargeRateStore store) {return new SetRechargeRateHandler(store);}
  @Bean BillingLedgerStore billingLedgerStore(BillingDatabase db) {return new MybatisLedgerStore(db);}
}
