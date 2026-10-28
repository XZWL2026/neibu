package com.xzwl.cloud.iam.interfaces.http;

import com.xzwl.cloud.iam.application.account.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Never cache account state: revoked versions remain invalid even after the account is re-enabled. */
public final class SessionStatusFilter extends OncePerRequestFilter {
  private final AdminAccounts admins;
  private final UserDirectory users;
  private final Clock clock;
  private final ObjectMapper json;
  public SessionStatusFilter(AdminAccounts admins, UserDirectory users, Clock clock, ObjectMapper json) {
    this.admins = admins; this.users = users; this.clock = clock; this.json = json;
  }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!request.getServletPath().equals("/api/v1/auth/logout") && authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
      try {
        var session = request.getSession(false);
        Long started = session == null ? null : (Long) session.getAttribute(SessionLogin.AUTHENTICATED_AT);
        boolean valid = started != null && (!principal.user() || clock.millis() < started + Duration.ofDays(180).toMillis());
        if (valid && principal.user()) {
          var user = users.find(principal.id());
          valid = user != null && user.enabled() && user.authVersion() == principal.version();
        } else if (valid) valid = admins.current(principal);
        if (!valid) {
          SecurityContextHolder.clearContext();
          if (session != null) session.invalidate();
        }
      } catch (AuthFailure failure) {
        response.setStatus(failure.status());
        response.setContentType("application/json");
        json.writeValue(response.getWriter(), Map.of("code", failure.getMessage()));
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
