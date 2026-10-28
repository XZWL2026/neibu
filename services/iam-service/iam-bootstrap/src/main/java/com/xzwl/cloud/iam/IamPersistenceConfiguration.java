package com.xzwl.cloud.iam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

import com.xzwl.cloud.iam.application.account.*;
import com.xzwl.cloud.iam.infrastructure.account.*;
import com.xzwl.cloud.iam.application.otp.*;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class IamPersistenceConfiguration {
  @Bean OtpCodes otpCodes() { return new OtpCodes(System.getenv("IAM_OTP_HMAC_KEY")); }
  @Bean OtpStore otpStore(PhoneOtpMapper otps, PlatformTransactionManager manager) { return new MyBatisOtpStore(otps, new TransactionTemplate(manager)); }
  @Bean OtpService otpService(OtpStore store, SmsOtpProvider provider, OtpCodes codes, UserDirectory users, Clock clock) {
    return new OtpService(store, provider, codes, users, clock);
  }
  @Bean @ConditionalOnMissingBean Clock clock() { return Clock.systemUTC(); }
  @Bean AdminStore adminStore(AdminMapper admins, PlatformTransactionManager manager) { return new MyBatisAdminStore(admins, new TransactionTemplate(manager)); }
  @Bean PasswordHasher passwordHasher() { return new BCryptPasswordHasher(); }
  @Bean AdminAccounts adminAccounts(AdminStore store, PasswordHasher passwords, UserDirectory users, PortalDirectory portals) { return new AdminAccounts(store, passwords, users, portals); }
  @Bean PortalHandoffs portalHandoffs(javax.sql.DataSource dataSource, PlatformTransactionManager manager,
      Clock clock, org.springframework.session.jdbc.JdbcIndexedSessionRepository sessions, AdminAccounts admins) {
    return new JdbcPortalHandoffs(new org.springframework.jdbc.core.JdbcTemplate(dataSource), new TransactionTemplate(manager), clock,
        (id, portal) -> {
          org.springframework.session.Session source=sessions.findById(id);
          if (source==null || source.isExpired()) return false;
          Long started=source.getAttribute(com.xzwl.cloud.iam.interfaces.http.SessionLogin.AUTHENTICATED_AT);
          org.springframework.security.core.context.SecurityContext context=source.getAttribute("SPRING_SECURITY_CONTEXT");
          if (started==null || context==null || context.getAuthentication()==null
              || !(context.getAuthentication().getPrincipal() instanceof AuthPrincipal actor)) return false;
          if (actor.user()) return clock.millis()<started+java.time.Duration.ofDays(180).toMillis()
              && actor.id().equals(portal.boundUserId()) && actor.version()==portal.boundUserVersion();
          return portal.portal() && "ADMIN".equals(actor.role()) && actor.primary() && admins.current(actor);

        });
  }
  @Bean PortalDirectory portalDirectory(@Qualifier("internalRestClientBuilder") RestClient.Builder builder,
      @Value("${iam.portal-access-service-url:${ACCESS_SERVICE_URL:http://127.0.0.1:8212}}") String access,
      @Value("${iam.portal-promotion-service-url:${PROMOTION_SERVICE_URL:http://127.0.0.1:8213}}") String promotion,
      @Value("${iam.portal-payment-service-url:${PAYMENT_SERVICE_URL:http://127.0.0.1:8215}}") String payment,
      @Value("${iam.portal-internal-token:${ABP_INTERNAL_TOKEN:}}") String token) {
    return new HttpPortalDirectory(builder,access, promotion, payment, token);
  }
  @Bean ApplicationRunner bootstrapAdministrator(AdminAccounts admins, Environment environment) {
    return args -> admins.bootstrap(System::getenv, !environment.acceptsProfiles(Profiles.of("local & !prod & !production")));
  }
  @Bean @ConditionalOnMissingBean UserDirectory userDirectory(@Qualifier("internalRestClientBuilder") RestClient.Builder builder, @Value("${iam.user-service-url}") String url) {
    return new HttpUserDirectory(builder,url, System.getenv("IAM_USER_INTERNAL_TOKEN"));
  }
}
