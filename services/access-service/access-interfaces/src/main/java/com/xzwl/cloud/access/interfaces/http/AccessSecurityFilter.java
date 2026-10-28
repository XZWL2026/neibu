package com.xzwl.cloud.access.interfaces.http;
import com.xzwl.cloud.access.application.identity.SessionVerifier;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
@Order(Ordered.HIGHEST_PRECEDENCE+10)
public final class AccessSecurityFilter extends OncePerRequestFilter {
  private final SessionVerifier sessions;private final byte[] internal;
  public AccessSecurityFilter(SessionVerifier sessions,String token){
    if(token==null||token.length()<32)throw new IllegalArgumentException("ABP_INTERNAL_TOKEN requires 32 characters");
    this.sessions=sessions;internal=token.getBytes(StandardCharsets.UTF_8);
  }
  protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
    String path=request.getRequestURI().substring(request.getContextPath().length());
    try{
      if(path.startsWith("/internal/v1/")){
        String token=request.getHeader("X-Service-Token");
        if(token==null||!MessageDigest.isEqual(internal,token.getBytes(StandardCharsets.UTF_8)))throw new SessionVerifier.Rejected(401);
      }else if(path.startsWith("/api/v1/")){
        var identity=sessions.verify(request.getHeader("Cookie"),request.getHeader("X-CSRF-TOKEN"),!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()));
        chain.doFilter(new HttpServletRequestWrapper(request){
          public Principal getUserPrincipal(){return identity::userId;}
          public boolean isUserInRole(String role){return identity.roles().contains(role);}
        },response);return;
      }
      chain.doFilter(request,response);
    }catch(SessionVerifier.Rejected e){response.setStatus(e.status);response.setContentType("application/json");response.getWriter().write("{\"code\":\"ACCESS_REJECTED\"}");}
  }
}
