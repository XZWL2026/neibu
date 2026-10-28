package com.xzwl.cloud.iam.interfaces.http;

import com.xzwl.cloud.iam.application.account.*;
import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local & !prod & !production")
public final class LocalAdminSessionController {
  private final AdminLoginHandler tokens;
  private final AdminAccounts admins;
  private final SessionLogin sessions;
  public LocalAdminSessionController(AdminLoginHandler tokens, AdminAccounts admins, SessionLogin sessions) { this.tokens = tokens; this.admins = admins; this.sessions = sessions; }
  @PostMapping("/api/v1/auth/admin/local-login")
  public Map<String, Boolean> login(@RequestBody Token body, HttpServletRequest request, HttpServletResponse response) {
    if (!tokens.authenticate(body.token())) throw new AuthFailure(401, "INVALID_CREDENTIALS");
    sessions.establish(admins.localPrimary(), request, response);
    return Map.of("success", true);
  }
  public record Token(String token) { @Override public String toString() { return "Token[redacted]"; } }
}
