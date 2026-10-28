package com.xzwl.cloud.iam.interfaces.http;

import com.xzwl.cloud.iam.application.account.AuthFailure;
import com.xzwl.cloud.iam.application.account.UserDirectory;
import com.xzwl.cloud.iam.application.otp.OtpService;
import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public final class PhoneLoginController {
  private final UserDirectory users;
  private final SessionLogin sessions;
  private final boolean enabled;

  public PhoneLoginController(UserDirectory users, SessionLogin sessions,
      @Value("${iam.phone-only-login.enabled:${IAM_PHONE_ONLY_LOGIN_ENABLED:false}}") boolean enabled) {
    this.users = users; this.sessions = sessions; this.enabled = enabled;
  }

  @PostMapping("/api/v1/auth/phone/login")
  public Map<String, Boolean> login(@RequestBody OtpController.Phone body,
      HttpServletRequest request, HttpServletResponse response) {
    if (!enabled) throw new AuthFailure(403, "PHONE_ONLY_LOGIN_DISABLED");
    var user = users.resolve(OtpService.phone(body.phone()));
    if (!user.enabled()) throw new AuthFailure(403, "USER_DISABLED");
    sessions.establish(user.principal(), request, response);
    return Map.of("success", true);
  }
}
