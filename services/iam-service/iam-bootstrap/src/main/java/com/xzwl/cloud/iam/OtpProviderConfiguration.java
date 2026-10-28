package com.xzwl.cloud.iam;

import com.xzwl.cloud.iam.application.account.AuthFailure;
import com.xzwl.cloud.iam.application.otp.SmsOtpProvider;
import com.xzwl.cloud.iam.infrastructure.sms.VolcanoSmsOtpProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.beans.factory.annotation.Value;

@Configuration(proxyBeanMethods = false)
public class OtpProviderConfiguration {
  @Bean @ConditionalOnMissingBean
  SmsOtpProvider smsOtpProvider(
      @Value("${iam.local-otp.enabled:${IAM_LOCAL_OTP_ENABLED:false}}") boolean enabled,
      @Value("${iam.local-otp.code:${IAM_LOCAL_OTP_CODE:123456}}") String localCode,
      Environment environment) {
    if (environment.getProperty("IAM_SMS_ENABLED", Boolean.class, false)) {
      if (enabled) throw new IllegalStateException("Real SMS and local fixed OTP cannot both be enabled");
      return new VolcanoSmsOtpProvider(environment.getProperty("IAM_SMS_ACCESS_KEY"),
          environment.getProperty("IAM_SMS_SECRET_KEY"), environment.getProperty("IAM_SMS_ACCOUNT"),
          environment.getProperty("IAM_SMS_SIGN"), environment.getProperty("IAM_SMS_TEMPLATE_ID"));
    }
    String provider = environment.getProperty("iam.sms.provider", environment.getProperty("IAM_SMS_PROVIDER", "disabled"));
    if (!provider.equals("disabled") && !provider.equals("volcengine"))
      throw new IllegalStateException("Unsupported SMS provider");
    if (provider.equals("volcengine")) {
      if (enabled) throw new IllegalStateException("Local OTP and Volcengine SMS cannot be enabled together");
      return new VolcengineSmsOtpProvider(System.getenv("IAM_VOLC_SMS_ACCESS_KEY"), System.getenv("IAM_VOLC_SMS_SECRET_KEY"),
          smsProperty(environment, "sms-account", "IAM_VOLC_SMS_ACCOUNT", ""),
          smsProperty(environment, "sign", "IAM_VOLC_SMS_SIGN", ""),
          smsProperty(environment, "template-id", "IAM_VOLC_SMS_TEMPLATE_ID", ""),
          smsProperty(environment, "code-param", "IAM_VOLC_SMS_CODE_PARAM", "code"),
          java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3))
              .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build(),
          java.net.URI.create("https://sms.volcengineapi.com"), java.time.Clock.systemUTC(), java.time.Duration.ofSeconds(5));
    }
    if (!enabled) return new SmsOtpProvider() {
      @Override public boolean acceptsCode(String code) { return false; }
      @Override public void send(String phone, String code) { throw new AuthFailure(503, "SMS_PROVIDER_UNAVAILABLE"); }
    };
    if (!environment.acceptsProfiles(Profiles.of("(local | test) & !prod & !production & !prod-fixed"))) {
      throw new IllegalStateException("Local OTP test mode is allowed only in local/test and never in prod/production/prod-fixed");
    }
    if (!localCode.matches("[0-9]{6}")) {
      throw new IllegalStateException("IAM_LOCAL_OTP_CODE must contain exactly six digits when test mode is enabled");
    }
    return new SmsOtpProvider() {
      @Override public String prepareCode(String generatedCode) { return localCode; }
      @Override public boolean acceptsCode(String code) { return localCode.equals(code); }
      @Override public void send(String phone, String code) {
        if (!localCode.equals(code)) throw new IllegalArgumentException("Local OTP preparation required");
        // Local delivery only: no SMS is sent and no code is logged.
      }
    };
  }

  private static String smsProperty(Environment environment, String name, String variable, String fallback) {
    return environment.getProperty("iam.sms.volcengine." + name, environment.getProperty(variable, fallback));
  }
}
