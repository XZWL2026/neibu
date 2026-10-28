package com.xzwl.cloud.iam;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.iam.application.otp.SmsOtpProvider;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class VolcanoSmsConfigurationTest {
  @Test void explicitlyEnabledCompleteConfigurationSelectsManagedCodes() {
    try (var app = context(settings())) {
      String first = app.getBean(SmsOtpProvider.class).prepareCode("123456");
      assertTrue(first.startsWith("volc:"));
      assertNotEquals(first, app.getBean(SmsOtpProvider.class).prepareCode("123456"));
    }
  }

  @Test void incompleteConfigurationAndSimultaneousLocalModeFailStartupWithoutSecrets() {
    for (String key : settings().keySet()) {
      if (key.equals("IAM_SMS_ENABLED")) continue;
      var config = settings(); config.put(key, " ");
      var error = assertThrows(org.springframework.beans.BeansException.class, () -> context(config));
      assertFalse(error.toString().contains("test-secret-never-print"));
    }
    var mixed = settings(); mixed.put("IAM_LOCAL_OTP_ENABLED", "true");
    assertThrows(org.springframework.beans.BeansException.class, () -> context(mixed));
  }

  @Test void credentialsAloneDoNotEnableSending() {
    var config = settings(); config.remove("IAM_SMS_ENABLED");
    try (var app = context(config)) {
      assertThrows(com.xzwl.cloud.iam.application.account.AuthFailure.class,
          () -> app.getBean(SmsOtpProvider.class).send("+8613800000001", "123456"));
    }
  }

  static HashMap<String,Object> settings() {
    return new HashMap<>(Map.of("IAM_SMS_ENABLED", "true", "IAM_SMS_ACCESS_KEY", "test-ak",
        "IAM_SMS_SECRET_KEY", "test-secret-never-print", "IAM_SMS_ACCOUNT", "test-account",
        "IAM_SMS_SIGN", "测试签名", "IAM_SMS_TEMPLATE_ID", "test-template"));
  }
  static AnnotationConfigApplicationContext context(Map<String,Object> settings) {
    var app = new AnnotationConfigApplicationContext();
    app.getEnvironment().setActiveProfiles("local");
    app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("sms-test", settings));
    app.register(OtpProviderConfiguration.class);
    try { app.refresh(); return app; }
    catch (RuntimeException failure) { app.close(); throw failure; }
  }
}
