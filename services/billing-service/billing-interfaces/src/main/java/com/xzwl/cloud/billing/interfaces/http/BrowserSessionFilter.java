package com.xzwl.cloud.billing.interfaces.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.billing.application.identity.SessionVerifier;
import com.xzwl.cloud.web.ErrorEnvelope;
import com.xzwl.cloud.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class BrowserSessionFilter extends OncePerRequestFilter {
  private final SessionVerifier verifier;
  private final ObjectMapper json;
  public BrowserSessionFilter(SessionVerifier verifier, ObjectMapper json) {
    this.verifier = verifier;
    this.json = json;
  }
  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/");
  }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    SessionVerifier.Identity identity;
    try {
      String session = sessionCookie(request);
      if (session == null) throw new SessionVerifier.Unauthenticated();
      boolean unsafe = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
      identity = verifier.verify("XZWL_SESSION=" + session, request.getHeader("X-CSRF-TOKEN"), unsafe);
    } catch (SessionVerifier.Unauthenticated failure) {
      writeError(request, response, HttpStatus.UNAUTHORIZED); return;
    } catch (SessionVerifier.CsrfDenied failure) {
      writeError(request, response, HttpStatus.FORBIDDEN); return;
    } catch (SessionVerifier.Unavailable failure) {
      writeError(request, response, HttpStatus.SERVICE_UNAVAILABLE); return;
    }
    if (identity.roles().contains("API_USER")) {
      String path = request.getRequestURI().substring(request.getContextPath().length());
      if (!request.getMethod().equals("GET") || !(path.startsWith("/api/v1/billing/portal/")
          || path.matches("/api/v1/billing/wallets/[A-Za-z0-9._:-]+(/ledger)?"))) {
        writeError(request, response, HttpStatus.FORBIDDEN); return;
      }
    }
    chain.doFilter(new HttpServletRequestWrapper(request) {
      @Override public Principal getUserPrincipal() { return identity::userId; }
      @Override public String getRemoteUser() { return identity.userId(); }
      @Override public boolean isUserInRole(String role) { return identity.roles().contains(role); }
    }, response);
  }
  private static String sessionCookie(HttpServletRequest request) {
    if (request.getCookies() == null) return null;
    for (Cookie cookie : request.getCookies()) {
      if (cookie.getName().equals("XZWL_SESSION") && cookie.getValue().matches("[A-Za-z0-9._-]{1,256}")) {
        return cookie.getValue();
      }
    }
    return null;
  }
  private void writeError(HttpServletRequest request, HttpServletResponse response, HttpStatus status) throws IOException {
    response.setStatus(status.value());
    response.setContentType("application/json");
    json.writeValue(response.getWriter(), new ErrorEnvelope(status.name(), status.getReasonPhrase(),
        (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), Map.of()));
  }
}
