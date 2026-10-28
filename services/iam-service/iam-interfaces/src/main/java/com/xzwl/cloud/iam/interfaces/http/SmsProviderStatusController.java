package com.xzwl.cloud.iam.interfaces.http;
import com.xzwl.cloud.iam.application.account.AuthPrincipal;
import com.xzwl.cloud.iam.application.account.AuthFailure;
import com.xzwl.cloud.iam.application.otp.SmsOtpProvider;
import com.xzwl.cloud.web.ProviderConfigurationStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
public final class SmsProviderStatusController {
  private final SmsOtpProvider provider;
  public SmsProviderStatusController(SmsOtpProvider provider) { this.provider = provider; }
  @GetMapping("/api/v1/admin/iam/sms-provider-status")
  public ProviderConfigurationStatus get(@AuthenticationPrincipal AuthPrincipal actor) {
    if(actor==null) throw new AuthFailure(401,"UNAUTHORIZED");
    if(!actor.primary() || !"ADMIN".equals(actor.role())) throw new AuthFailure(403,"FORBIDDEN");
    boolean configured = provider.productionConfigured();
    return new ProviderConfigurationStatus("火山短信",configured,configured,configured ? "***" : "",java.util.List.of());
  }
}
