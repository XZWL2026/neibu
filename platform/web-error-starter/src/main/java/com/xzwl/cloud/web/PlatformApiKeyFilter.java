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

/** Opt-in API authentication. Browser requests continue through their existing IAM/CSRF filters. */
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public final class PlatformApiKeyFilter extends OncePerRequestFilter {
  private final RestClient access;
  private final String serviceToken;
  public PlatformApiKeyFilter(String baseUrl,String serviceToken) { this(RestClient.builder(),baseUrl,serviceToken); }
  public PlatformApiKeyFilter(RestClient.Builder builder,String baseUrl,String serviceToken) {
    this.serviceToken=serviceToken;
    var factory=new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));factory.setReadTimeout(Duration.ofSeconds(3));
    access=builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
  }
  public static boolean accepts(String method,String path) {
    return switch(method) {
      case "POST" -> Set.of("/api/v1/media/quotes","/api/v1/media/tasks","/api/v1/media/portraits","/api/v1/files/reference-images").contains(path);
      case "GET" -> Set.of("/api/v1/media/generation-options","/api/v1/media/tasks","/api/v1/media/portraits").contains(path)
          || path.matches("/api/v1/media/portraits/[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
          || path.matches("/api/v1/media/tasks/[A-Za-z0-9._:-]+(?:/results)?")
          || path.matches("/api/v1/files/[A-Za-z0-9._:-]+/access-url");
      default -> false;
    };
  }
  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    return !accepts(request.getMethod(),request.getRequestURI().substring(request.getContextPath().length()))
        || request.getHeader("Authorization")==null;
  }
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws IOException,ServletException {
    var headers=Collections.list(request.getHeaders("Authorization"));
    if(headers.size()!=1 || !headers.getFirst().matches("(?i:Bearer) sk-(?:[a-z0-9]{32}|_[0-9a-f]{64})")) { reject(response,401);return; }
    if(serviceToken==null || serviceToken.isBlank()) { reject(response,503);return; }
    Identity identity;
    try {
      identity=access.post().uri("/internal/v1/access/authorize").header("X-Service-Token",serviceToken)
          .body(Map.of("secret",headers.getFirst().substring(7),"capability","API")).retrieve().body(Identity.class);
    } catch(RestClientResponseException failure) {
      int status=failure.getStatusCode().value();reject(response,status==401 || status==403 ? 401 : 503);return;
    } catch(RestClientException failure) {reject(response,503);return;}
    if(identity==null || !identity.enabled() || !"API".equals(identity.kind()) || !"API".equals(identity.accountType())
        || identity.ownerId()==null || !identity.ownerId().matches("[A-Za-z0-9._:-]{1,128}")) {reject(response,401);return;}
    var principal=new ApiPrincipal(identity.ownerId());
    chain.doFilter(new HttpServletRequestWrapper(request) {
      @Override public Principal getUserPrincipal(){return principal;}
      @Override public String getRemoteUser(){return principal.getName();}
      @Override public boolean isUserInRole(String role){return "USER".equals(role);}
    },response);
  }
  private static void reject(HttpServletResponse response,int status)throws IOException {
    response.setStatus(status);response.setContentType("application/json");response.setHeader("Cache-Control","no-store");
    response.getWriter().write("{\"code\":\""+(status==401?"API_KEY_DENIED":"ACCESS_UNAVAILABLE")+"\"}");
  }
  record ApiPrincipal(String name) implements Principal {public String getName(){return name;}}
  public record Identity(String ownerId,String accountType,String kind,boolean enabled){}
}
