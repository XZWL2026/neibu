package com.xzwl.cloud.billing.interfaces.http;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
@Order(Ordered.HIGHEST_PRECEDENCE+9)
public final class BillingInternalFilter extends OncePerRequestFilter {
  private final byte[] token;
  public BillingInternalFilter(String token){if(token==null||token.length()<32)throw new IllegalArgumentException("ABP_INTERNAL_TOKEN requires 32 characters");this.token=token.getBytes(StandardCharsets.UTF_8);}
  protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().startsWith(request.getContextPath()+"/internal/v1/");}
  protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
    String provided=request.getHeader("X-Service-Token");
    if(provided==null||!MessageDigest.isEqual(token,provided.getBytes(StandardCharsets.UTF_8))){response.setStatus(401);return;}
    chain.doFilter(request,response);
  }
}
