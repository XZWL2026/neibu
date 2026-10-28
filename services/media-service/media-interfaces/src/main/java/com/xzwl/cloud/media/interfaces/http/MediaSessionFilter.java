package com.xzwl.cloud.media.interfaces.http;
import com.xzwl.cloud.media.application.task.MediaSessionPort;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.Principal;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/** Revalidates existing IAM browser sessions; never trusts caller-supplied identity headers. */
public final class MediaSessionFilter extends OncePerRequestFilter {
  private final MediaSessionPort sessions;
  public MediaSessionFilter(MediaSessionPort sessions) { this.sessions=sessions; }
  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(request.getContextPath()+"/api/v1/media/");
  }
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws IOException,ServletException {
    if(request.getUserPrincipal()!=null) { chain.doFilter(request,response); return; }
    try {
      String cookie=sessionCookie(request);
      if(cookie==null) throw new MediaSessionPort.Denied(401);
      String owner=sessions.verify(cookie,request.getHeader("X-CSRF-TOKEN"),!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod()));
      chain.doFilter(new HttpServletRequestWrapper(request) {
        @Override public Principal getUserPrincipal() { return () -> owner; }
        @Override public String getRemoteUser() { return owner; }
      },response);
    } catch(MediaSessionPort.Denied denied) {
      response.setStatus(denied.status()); response.setContentType("application/json");
      response.getWriter().write("{\"code\":\"MEDIA_SESSION_DENIED\"}");
    }
  }
  private String sessionCookie(HttpServletRequest request) {
    if(request.getCookies()!=null) for(Cookie cookie:request.getCookies()) {
      if(cookie.getName().equals("XZWL_SESSION") && cookie.getValue().matches("[A-Za-z0-9._-]{1,256}"))
        return "XZWL_SESSION="+cookie.getValue();
    }
    return null;
  }
}
