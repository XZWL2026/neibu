package com.xzwl.cloud.promotion;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

import com.xzwl.cloud.promotion.application.production.PromotionService;
import com.xzwl.cloud.promotion.application.production.PromotionStore;
import com.xzwl.cloud.promotion.infrastructure.MyBatisPromotionStore;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.xzwl.cloud.promotion.infrastructure.persistence.PromotionMappers.*;
import com.xzwl.cloud.promotion.infrastructure.persistence.PromotionSqlMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@org.springframework.scheduling.annotation.EnableScheduling
@Configuration(proxyBeanMethods = false)
public class PromotionProductionConfiguration {
  @Bean
  PromotionStore promotionStore(@Qualifier("internalRestClientBuilder") RestClient.Builder builder, Eligibilities eligibilities,
      Applications applications,
      Promoters promoters,
      Receipts receipts,
      Referrals referrals,
      Policies policies,
      Accounts accounts,
      FundingBenefits fundingBenefits,
      Ledger ledger,
      Payouts payouts,
      Withdrawals withdrawals,
      PromotionSqlMapper sql, PlatformTransactionManager manager,
      @org.springframework.beans.factory.annotation.Value("${promotion.user-service-url:${USER_SERVICE_URL:http://127.0.0.1:8211}}") String userUrl,
      @org.springframework.beans.factory.annotation.Value("${promotion.billing-service-url:${BILLING_SERVICE_URL:http://127.0.0.1:8214}}") String billingUrl,
      @org.springframework.beans.factory.annotation.Value("${promotion.user-service-token:${IAM_USER_INTERNAL_TOKEN:}}") String userToken,
      @org.springframework.beans.factory.annotation.Value("${promotion.payment-service-url:${PAYMENT_SERVICE_URL:http://127.0.0.1:8215}}") String paymentUrl,
      @org.springframework.beans.factory.annotation.Value("${abp.internal-token:${ABP_INTERNAL_TOKEN:}}") String internalToken) {
    var store = new MyBatisPromotionStore(eligibilities, applications, promoters, receipts, referrals, policies, accounts, fundingBenefits, ledger, payouts, withdrawals,
        sql, manager);
    var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(java.time.Duration.ofSeconds(2)); factory.setReadTimeout(java.time.Duration.ofSeconds(5));
    var directory = new PromotionPortalDirectory(
        builder.clone().baseUrl(userUrl).requestFactory(factory).defaultHeader("X-IAM-INTERNAL-TOKEN", userToken).build(),
        builder.clone().baseUrl(billingUrl).requestFactory(factory).defaultHeader("X-Service-Token", internalToken).build());
    directory.setPayment(builder.clone().baseUrl(paymentUrl).requestFactory(factory).defaultHeader("X-Service-Token", internalToken).build());
    store.setLiveApplicants(directory::applicant);
    store.setPortalDirectory(directory);
    store.setAdminUserDirectory(directory::enabledUser);
    store.setPortalAllocationDirectory(directory::allocation);
    return store;
  }

  @Bean
  com.xzwl.cloud.promotion.application.production.PromotionOperations promotionOperations(
      PromotionStore store, javax.sql.DataSource dataSource, PlatformTransactionManager manager) {
    var operations = new com.xzwl.cloud.promotion.infrastructure.MybatisPromotionOperations(
        new org.springframework.jdbc.core.JdbcTemplate(dataSource), store, manager, java.time.Clock.systemUTC());
    ((MyBatisPromotionStore)store).setPaidRechargeProjection(operations::recordRecharge);
    return operations;
  }

  @Bean
  com.xzwl.cloud.promotion.infrastructure.HttpReferralRewardDelivery referralRewardDelivery(@Qualifier("internalRestClientBuilder") RestClient.Builder builder,
      com.xzwl.cloud.promotion.application.production.PromotionOperations operations,
      @org.springframework.beans.factory.annotation.Value("${promotion.billing-service-url:${BILLING_SERVICE_URL:http://127.0.0.1:8214}}") String billingUrl,
      @org.springframework.beans.factory.annotation.Value("${abp.internal-token:${ABP_INTERNAL_TOKEN:}}") String token) {
    var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(java.time.Duration.ofSeconds(2));factory.setReadTimeout(java.time.Duration.ofSeconds(5));
    var client=builder.clone().baseUrl(billingUrl).requestFactory(factory).defaultHeader("X-Service-Token",token).build();
    ((com.xzwl.cloud.promotion.infrastructure.MybatisPromotionOperations)operations).setRewardDebtLookup(owner->{
      var balance=client.get().uri("/internal/v1/billing/referral-rewards/{owner}/balance",owner).retrieve().body(RewardBalance.class);
      if(balance==null)throw new IllegalStateException("reward debt service unavailable");return balance.debtPoints4();
    });
    return new com.xzwl.cloud.promotion.infrastructure.HttpReferralRewardDelivery(operations,
        command->client.post().uri("/internal/v1/billing/referral-rewards").body(java.util.Map.of(
            "eventId",command.eventId(),"kind",command.kind(),"rewardEntitlementId",command.rewardEntitlementId(),
            "beneficiaryUserId",command.beneficiaryUserId(),"sourceSettlementId",command.sourceSettlementId(),
            "sourceAllocationId",command.sourceAllocationId(),"rewardPoints4",command.rewardPoints4(),
            "occurredAt",command.occurredAt().toString())).retrieve()
            .body(com.xzwl.cloud.promotion.application.production.PromotionOperations.RewardReceipt.class));
  }
  @Bean
  RewardWorker promotionRewardWorker(com.xzwl.cloud.promotion.infrastructure.HttpReferralRewardDelivery delivery) { return new RewardWorker(delivery); }
  record RewardBalance(String debtPoints4) {}
  record RewardWorker(com.xzwl.cloud.promotion.infrastructure.HttpReferralRewardDelivery delivery) {
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${promotion.reward-delay-ms:3000}")
    public void run() {delivery.tick();}
  }

  @Bean
  com.xzwl.cloud.promotion.application.production.AdminAgentDirectory adminAgentDirectory(
      @Qualifier("internalRestClientBuilder") RestClient.Builder builder,
      javax.sql.DataSource dataSource,PromotionStore store,
      @org.springframework.beans.factory.annotation.Value("${promotion.user-service-url:${USER_SERVICE_URL:http://127.0.0.1:8211}}") String userUrl,
      @org.springframework.beans.factory.annotation.Value("${promotion.billing-service-url:${BILLING_SERVICE_URL:http://127.0.0.1:8214}}") String billingUrl,
      @org.springframework.beans.factory.annotation.Value("${promotion.payment-service-url:${PAYMENT_SERVICE_URL:http://127.0.0.1:8215}}") String paymentUrl,
      @org.springframework.beans.factory.annotation.Value("${promotion.user-service-token:${IAM_USER_INTERNAL_TOKEN:}}") String userToken,
      @org.springframework.beans.factory.annotation.Value("${abp.internal-token:${ABP_INTERNAL_TOKEN:}}") String internalToken) {
    var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(java.time.Duration.ofSeconds(2));factory.setReadTimeout(java.time.Duration.ofSeconds(5));
    return new AdminAgentDirectoryReader(new org.springframework.jdbc.core.JdbcTemplate(dataSource),store,
        builder.clone().baseUrl(userUrl).requestFactory(factory).defaultHeader("X-IAM-INTERNAL-TOKEN",userToken).build(),
        builder.clone().baseUrl(paymentUrl).requestFactory(factory).defaultHeader("X-Service-Token",internalToken).build(),
        builder.clone().baseUrl(billingUrl).requestFactory(factory).defaultHeader("X-Service-Token",internalToken).build(),java.time.Clock.systemUTC());
  }

  @Bean
  MybatisPlusInterceptor promotionPagination() {
    var interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
  }

  @Bean
  PromotionService promotionService(PromotionStore store) {
    return new PromotionService(store);
  }
}
