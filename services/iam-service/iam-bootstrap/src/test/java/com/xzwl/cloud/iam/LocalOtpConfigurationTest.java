package com.xzwl.cloud.iam;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.iam.application.account.AuthFailure;
import com.xzwl.cloud.iam.application.otp.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import java.util.function.Consumer;

class LocalOtpConfigurationTest {
  @Test void localProfileHonorsTheExplicitDatabaseUrl() throws Exception {
    var env = new org.springframework.core.env.StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("private-runtime", Map.of("IAM_DB_URL", "jdbc:mysql://127.0.0.1:13316/iam_db")));
    var yaml = new org.springframework.boot.env.YamlPropertySourceLoader().load("local",
        new org.springframework.core.io.ClassPathResource("application-local.yml"));
    yaml.forEach(source -> env.getPropertySources().addLast(source));
    assertEquals("jdbc:mysql://127.0.0.1:13316/iam_db", env.getProperty("spring.datasource.url"));
  }

  @Test void localDeliveryStillUsesOtpHashQuotaExpiryAndOneTimeConsumption() {
    context("local", "246810", app -> {
      var provider = app.getBean(SmsOtpProvider.class);
      var codes = new OtpCodes("test-only-hmac-material-at-least-32-bytes");
      class Store implements OtpStore {
        com.xzwl.cloud.iam.domain.account.PhoneOtp state = new com.xzwl.cloud.iam.domain.account.PhoneOtp("+8613800000001", null, 0, null, null, 0, 0, true);
        public <T> T locked(String phone, java.util.function.Function<com.xzwl.cloud.iam.domain.account.PhoneOtp,T> work) { return work.apply(state); }
        public void save(com.xzwl.cloud.iam.domain.account.PhoneOtp value) { state = value; }
      }
      var store = new Store();
      var users = new com.xzwl.cloud.iam.application.account.UserDirectory() {
        public UserIdentity resolve(String phone) { return new UserIdentity("test-user", phone, "Test", true, 0); }
        public UserIdentity find(String id) { return resolve("+8613800000001"); }
      };
      var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-08T00:00:00Z"), java.time.ZoneOffset.UTC);
      var service = new OtpService(store, provider, codes, users, clock);
      service.send("13800000001");
      assertTrue(codes.matches("+8613800000001", "246810", store.state.codeHash()));
      assertEquals("OTP_RESEND_TOO_SOON", assertThrows(AuthFailure.class, () -> service.send("13800000001")).getMessage());
      assertThrows(AuthFailure.class, () -> service.login("13800000001", "135790"));
      assertEquals(1, store.state.failures());
      var resend = new OtpService(store, provider, codes, users, java.time.Clock.offset(clock, java.time.Duration.ofSeconds(61)));
      assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> resend.send("13800000001"));
      assertEquals(2, store.state.sentCount());
      assertTrue(resend.login("13800000001", "246810").user());
      assertThrows(AuthFailure.class, () -> resend.login("13800000001", "246810"));
      var later = new OtpService(store, provider, codes, users, java.time.Clock.offset(clock, java.time.Duration.ofSeconds(122)));
      later.send("13800000001");
      var expired = new OtpService(store, provider, codes, users, java.time.Clock.offset(clock, java.time.Duration.ofSeconds(423)));
      assertEquals("OTP_INVALID_OR_EXPIRED", assertThrows(AuthFailure.class, () -> expired.login("13800000001", "246810")).getMessage());
    });
  }

  private void context(String profiles, String code, Consumer<AnnotationConfigApplicationContext> check) {
    try (var app = new AnnotationConfigApplicationContext()) {
      app.getEnvironment().setActiveProfiles(profiles.split(","));
      app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("local-test", Map.of("IAM_LOCAL_OTP_ENABLED", "true", "IAM_LOCAL_OTP_CODE", code)));
      app.register(OtpProviderConfiguration.class);
      app.refresh();
      check.accept(app);
    }
  }

  @Test void localCodeRequiresExplicitSixDigitConfiguration() {
    for (String value : new String[]{"", "short", "1234567"}) {
      assertThrows(org.springframework.beans.BeansException.class, () -> context("local", value, app -> {}));
    }
    context("local", "246810", app -> {
          var provider = app.getBean(SmsOtpProvider.class);
          assertEquals("246810", provider.prepareCode("135790"));
          assertDoesNotThrow(() -> provider.send("+8613800000001", "246810"));
          assertThrows(IllegalArgumentException.class, () -> provider.send("+8613800000001", "135790"));
        });
  }

  @Test void disabledByDefaultEvenWhenCodeIsConfigured() {
    for (String profile : new String[]{"local", "test", "default", "prod", "production", "prod-fixed", "local,prod-fixed", "test,prod-fixed"}) {
      context(profile, Map.of("IAM_LOCAL_OTP_CODE", "123456"), app -> unavailable(app));
      context(profile, Map.of("IAM_LOCAL_OTP_ENABLED", "false", "IAM_LOCAL_OTP_CODE", "123456"), app -> unavailable(app));
    }
  }

  @Test void enabledLocalAndTestDefaultTo123456AndSupportProperties() {
    for (String profile : new String[]{"local", "test", "local,test"}) {
      context(profile, Map.of("IAM_LOCAL_OTP_ENABLED", "true"), app ->
          assertEquals("123456", app.getBean(SmsOtpProvider.class).prepareCode("135790")));
    }
    context("test", Map.of("iam.local-otp.enabled", "true", "iam.local-otp.code", "654321"), app ->
        assertEquals("654321", app.getBean(SmsOtpProvider.class).prepareCode("135790")));
  }

  @Test void enabledModeFailsStartupOutsideLocalOrTestIncludingMixedProductionProfiles() {
    for (String profile : new String[]{"default", "staging", "prod", "production", "local,prod", "local,production", "test,prod", "test,production", "prod-fixed", "local,prod-fixed", "test,prod-fixed"}) {
      assertThrows(org.springframework.beans.BeansException.class, () -> context(profile, "123456", app -> {}));
    }
  }

  private void unavailable(AnnotationConfigApplicationContext app) {
    var provider = app.getBean(SmsOtpProvider.class);
    assertEquals("135790", provider.prepareCode("135790"));
    assertEquals("SMS_PROVIDER_UNAVAILABLE", assertThrows(AuthFailure.class,
        () -> provider.send("+8613800000001", "123456")).getMessage());
  }

  private void context(String profiles, Map<String,Object> properties, Consumer<AnnotationConfigApplicationContext> check) {
    try (var app = new AnnotationConfigApplicationContext()) {
      app.getEnvironment().setActiveProfiles(profiles.split(","));
      app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("local-test", properties));
      app.register(OtpProviderConfiguration.class);
      app.refresh();
      check.accept(app);
    }
  }
}
