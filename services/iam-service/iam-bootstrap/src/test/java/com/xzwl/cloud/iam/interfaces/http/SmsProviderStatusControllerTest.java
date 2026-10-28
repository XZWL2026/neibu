package com.xzwl.cloud.iam.interfaces.http;
import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.iam.application.account.*;
import org.junit.jupiter.api.Test;
class SmsProviderStatusControllerTest {
  @Test void onlyPrimarySeesProductionConfigurationWithoutCredentials() {
    var controller=new SmsProviderStatusController((phone,code)->{});
    for(String role:new String[]{"ADMIN","NORMAL_ADMIN"}) assertThrows(AuthFailure.class,
        ()->controller.get(new AuthPrincipal("id","login",role,false,1)));
    var status=controller.get(new AuthPrincipal("id","login","ADMIN",true,1));
    assertFalse(status.available());assertFalse(status.configured());assertTrue(status.missingEnvironmentVariables().isEmpty());
    assertThrows(AuthFailure.class, () -> controller.get(null));
    var real = new SmsProviderStatusController(new com.xzwl.cloud.iam.infrastructure.sms.VolcanoSmsOtpProvider(
        "test-ak", "test-secret", "test-account", "test-sign", "test-template"));
    var configured = real.get(new AuthPrincipal("id","login","ADMIN",true,1));
    assertTrue(configured.configured()); assertTrue(configured.available());
    assertEquals("火山短信", configured.providerName()); assertEquals("***", configured.maskedIdentifier());
  }
}
