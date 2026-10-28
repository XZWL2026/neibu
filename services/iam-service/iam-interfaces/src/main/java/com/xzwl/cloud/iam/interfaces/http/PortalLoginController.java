package com.xzwl.cloud.iam.interfaces.http;

import com.xzwl.cloud.iam.application.account.*;
import jakarta.servlet.http.*;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public final class PortalLoginController {
  private final AdminAccounts admins;
  private final PortalHandoffs handoffs;
  private final SessionLogin sessions;
  public PortalLoginController(AdminAccounts admins, PortalHandoffs handoffs, SessionLogin sessions) {
    this.admins=admins; this.handoffs=handoffs; this.sessions=sessions;
  }
  @PostMapping("/api/v1/admin/admins/{id}/agent-ticket")
  public Ticket issueAgent(@PathVariable("id") String id, @RequestBody AgentRequest input, Authentication auth, HttpServletRequest request, HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    var principal=admins.enterAgent((AuthPrincipal)auth.getPrincipal(),id);
    return new Ticket(handoffs.issue(principal,request.getSession().getId(),input.challenge()));
  }
  @PostMapping("/api/v1/admin/api-users/{id}/agent-ticket")
  public Ticket issueApiUser(@PathVariable("id") String id, @RequestBody AgentRequest input, Authentication auth, HttpServletRequest request, HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    var principal=admins.enterApiUser((AuthPrincipal)auth.getPrincipal(),id);
    return new Ticket(handoffs.issue(principal,request.getSession().getId(),input.challenge()));
  }
  @GetMapping("/api/v1/auth/portal/options")
  public Options options(Authentication auth, HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    var roles=new ArrayList<String>();
    for (String role : List.of("API_USER","PROMOTER")) {
      try { admins.portalFor((AuthPrincipal)auth.getPrincipal(),role); roles.add(role); }
      catch (AuthFailure failure) { if (failure.status()!=403) throw failure; }
    }
    return new Options(roles);
  }
  @PostMapping("/api/v1/auth/portal/tickets")
  public Ticket issue(@RequestBody Request input, Authentication auth, HttpServletRequest request, HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    var principal=admins.portalFor((AuthPrincipal)auth.getPrincipal(),input.role());
    return new Ticket(handoffs.issue(principal,request.getSession().getId(),input.challenge()));
  }
  @PostMapping("/api/v1/auth/portal/login")
  public Result redeem(@RequestBody Exchange input, Authentication auth, HttpServletRequest request, HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");
    if (auth!=null && auth.getPrincipal() instanceof AuthPrincipal actor && actor.user()) throw new AuthFailure(403,"INDEPENDENT_PORTAL_SESSION_REQUIRED");
    var principal=handoffs.redeem(input.ticket(),input.verifier());
    if (!admins.current(principal)) throw new AuthFailure(403,"PORTAL_ACCESS_DENIED");
    sessions.establish(principal,request,response);
    return new Result(principal.role());
  }
  public record Options(List<String> roles) {}
  public record Request(String role,String challenge) {}
  public record AgentRequest(String challenge) {}
  public record Ticket(String ticket) { @Override public String toString() { return "Ticket[redacted]"; } }
  public record Exchange(String ticket,String verifier) { @Override public String toString() { return "Exchange[redacted]"; } }
  public record Result(String role) {}
}
