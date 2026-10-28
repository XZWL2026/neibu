package com.xzwl.cloud.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.util.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Opt-in browser bridge for services without their own IAM filter. Internal authentication is unchanged. */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class BrowserIamSessionFilter extends OncePerRequestFilter {
  private final RestClient iam;
  private final String pathPrefix;
  public BrowserIamSessionFilter(String baseUrl) { this(baseUrl,"/api/v1/"); }
  public BrowserIamSessionFilter(String baseUrl,String pathPrefix) { this(RestClient.builder(),baseUrl,pathPrefix); }
  public BrowserIamSessionFilter(RestClient.Builder builder,String baseUrl,String pathPrefix) {
    this.pathPrefix=pathPrefix;
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(3));
    iam = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
  }
  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !path.startsWith(pathPrefix) || path.startsWith("/api/v1/internal/");
  }
  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getUserPrincipal() instanceof PlatformApiKeyFilter.ApiPrincipal) { chain.doFilter(request, response); return; }
    String cookie = null;
    if (request.getCookies() != null) for (Cookie candidate : request.getCookies()) {
      if (candidate.getName().equals("XZWL_SESSION") && candidate.getValue().matches("[A-Za-z0-9._-]{1,256}")) {
        if (cookie != null) { reject(response, 401); return; }
        cookie = "XZWL_SESSION=" + candidate.getValue();
      }
    }
    if (cookie == null) { reject(response, 401); return; }
    boolean unsafe = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
    String csrf = request.getHeader("X-CSRF-TOKEN");
    if (unsafe && (csrf == null || csrf.isBlank())) { reject(response, 403); return; }
    Identity identity;
    try {
      RestClient.RequestHeadersSpec<?> call = unsafe
          ? iam.post().uri("/internal/v1/iam/session/verify").body(Map.of())
          : iam.get().uri("/internal/v1/iam/session");
      call.header("Cookie", cookie);
      if (csrf != null) call.header("X-CSRF-TOKEN", csrf);
      identity = call.retrieve().body(Identity.class);
    } catch (RestClientResponseException failure) {
      int code = failure.getStatusCode().value();
      reject(response, code == 401 || code == 403 ? code : 503); return;
    } catch (RestClientException failure) { reject(response, 503); return; }
    if (identity == null || !identity.authenticated() || identity.user() == null || identity.user().id() == null
        || identity.user().id().isBlank() || identity.roles() == null || identity.roles().isEmpty()) {
      reject(response, 401); return;
    }
    if (identity.roles().stream().anyMatch(role -> role == null || !Set.of("user", "admin", "normal_admin", "promoter", "api_user").contains(role))) {
      reject(response, 403); return;
    }
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (identity.roles().contains("api_user") || (identity.roles().contains("promoter")
        && !path.startsWith("/api/v1/promotions/portal/"))) { reject(response, 403); return; }
    Set<String> roles = new HashSet<>();
    identity.roles().forEach(role -> roles.add(role.toUpperCase(Locale.ROOT)));
    if (identity.primary() && roles.contains("ADMIN")) roles.add("PRIMARY_ADMIN");
    String userId = identity.user().id();
    chain.doFilter(new HttpServletRequestWrapper(request) {
      @Override public Principal getUserPrincipal() { return () -> userId; }
      @Override public String getRemoteUser() { return userId; }
      @Override public boolean isUserInRole(String role) { return roles.contains(role); }
    }, response);
  }
  private static void reject(HttpServletResponse response, int status) throws IOException {
    response.setStatus(status); response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response.getWriter().write("{\"code\":\"" + (status == 401 ? "UNAUTHORIZED" : status == 403 ? "FORBIDDEN" : "IAM_UNAVAILABLE") + "\"}");
  }
  public record Identity(boolean authenticated, List<String> roles, User user, boolean primary) {
    public Identity(boolean authenticated, List<String> roles, User user) { this(authenticated, roles, user, false); }
  }
  public record User(String id) {}
}
