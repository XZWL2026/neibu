package com.xzwl.cloud.storage.interfaces.http;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import org.springframework.web.filter.OncePerRequestFilter;

/** The media credential grants only the task operation, never administrator privileges. */
public final class StorageMediaFilter extends OncePerRequestFilter {
  private final byte[] token;
  public StorageMediaFilter(String token) {
    if (!token.isEmpty() && token.length()<32) throw new IllegalArgumentException("media token requires 32 characters");
    this.token=token.getBytes(StandardCharsets.UTF_8);
  }
  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    return !(request.getRequestURI().equals("/internal/v1/files/access-url")
        || request.getRequestURI().equals("/internal/v1/files/media-results")
        || request.getRequestURI().equals("/internal/v1/files/processing-videos/access-url")
        || request.getRequestURI().equals("/internal/v1/files/media-results/archive-url")
        || request.getRequestURI().equals("/internal/v1/files/reference-images/access-url")
        || request.getRequestURI().equals("/internal/v1/files/reference-media/access-url"));
  }
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws IOException,ServletException {
    String supplied=request.getHeader("X-Service-Token");
    if(token.length==0 || supplied==null || !MessageDigest.isEqual(token,supplied.getBytes(StandardCharsets.UTF_8))) {
      response.setStatus(401); return;
    }
    chain.doFilter(new HttpServletRequestWrapper(request) {
      @Override public Principal getUserPrincipal() { return ()->"media-service"; }
      @Override public String getRemoteUser() { return "media-service"; }
      @Override public boolean isUserInRole(String role) { return false; }
    },response);
  }
}
