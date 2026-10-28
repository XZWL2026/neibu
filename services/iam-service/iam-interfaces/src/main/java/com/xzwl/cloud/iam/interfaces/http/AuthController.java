package com.xzwl.cloud.iam.interfaces.http;

import com.xzwl.cloud.iam.api.*;
import com.xzwl.cloud.iam.application.account.*;
import jakarta.servlet.http.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
public final class AuthController {
  private final AdminAccounts admins;
  private final SessionLogin sessions;
  public AuthController(AdminAccounts admins, SessionLogin sessions) { this.admins = admins; this.sessions = sessions; }
  @PostMapping("/api/v1/auth/admin/login")
  public Map<String, Boolean> login(@RequestBody Credentials credentials, HttpServletRequest request, HttpServletResponse response) {
    sessions.establish(admins.login(credentials.username(), credentials.password()), request, response);
    return Map.of("success", true);
  }
  @GetMapping({"/api/v1/auth/session", "/internal/v1/iam/session"})
  public SessionDTO session(Authentication auth, CsrfToken csrf) {
    if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal p)) return new SessionDTO(false, List.of(), null, csrf.getToken(), false);
    return new SessionDTO(true, List.of(p.role().toLowerCase(Locale.ROOT)), new UserDTO(p.ownerId(), p.login()), csrf.getToken(), p.primary());
  }
  @PostMapping("/internal/v1/iam/session/verify")
  public SessionDTO verify(Authentication auth, CsrfToken csrf) { return session(auth, csrf); }
  public record Credentials(String username, String password) { @Override public String toString() { return "Credentials[redacted]"; } }
}
