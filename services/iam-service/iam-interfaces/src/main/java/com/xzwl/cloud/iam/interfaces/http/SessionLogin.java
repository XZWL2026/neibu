package com.xzwl.cloud.iam.interfaces.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import com.xzwl.cloud.iam.application.account.AuthPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Persist the verified identity after applying session-fixation and CSRF-token protection. */
public final class SessionLogin {
  private final SecurityContextRepository contexts;
  private final SessionAuthenticationStrategy sessions;
  private final Clock clock;
  public static final String AUTHENTICATED_AT = "iam.authenticatedAt";
  public SessionLogin(SecurityContextRepository contexts, SessionAuthenticationStrategy sessions, Clock clock) {
    this.contexts = contexts;
    this.sessions = sessions;
    this.clock = clock;
  }
  public void establish(AuthPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
    Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
    request.getSession();
    sessions.onAuthentication(authentication, request, response);
    request.getSession().setMaxInactiveInterval((int) (principal.user() ? Duration.ofDays(30) : Duration.ofHours(24)).toSeconds());
    request.getSession().setAttribute(AUTHENTICATED_AT, clock.millis());
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
  }
}
