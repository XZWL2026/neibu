package com.xzwl.cloud.iam;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="IAM_TEST_MYSQL_URL", matches=".+")
class PortalAuthMysqlTest {
  private static final String VERIFIER="a".repeat(64);
  private static final String CHALLENGE="ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb";
  @Test void realRoleLoginBindsOwnerAndCannotAcquireAdministration() throws Exception {
    var enabled = new AtomicBoolean(true);
    var eligible = new AtomicBoolean(true);
    var accountBound = new AtomicBoolean(false);
    var paymentBound = new AtomicBoolean(false);
    var userVersion = new java.util.concurrent.atomic.AtomicLong();
    var invalidRequests = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      String token = exchange.getRequestHeaders().getFirst(path.startsWith("/internal/v1/users/") ? "X-IAM-INTERNAL-TOKEN" : "X-Service-Token");
      String expectedToken = path.startsWith("/internal/v1/users/") ? System.getenv("IAM_USER_INTERNAL_TOKEN") : "portal-test-only-token-0000000000000000";
      if (!"GET".equals(exchange.getRequestMethod()) || !expectedToken.equals(token)
          || !java.util.Set.of("/internal/v1/users/business-user", "/internal/v1/access/owners/business-user/account-identity", "/internal/v1/payments/accounts/business-user/identity", "/api/v1/internal/promotions/promoters/business-user/portal-eligibility").contains(path)) invalidRequests.add(path);
      String body = path.endsWith("account-identity") || path.endsWith("/identity")
          ? "{\"ownerId\":\"business-user\",\"accountType\":"+((path.endsWith("account-identity")?accountBound.get():paymentBound.get())?"\""+(eligible.get()?"API":"PERSONAL")+"\"":"null")+"}"
          : path.endsWith("portal-eligibility")
          ? "{\"userId\":\"business-user\",\"eligible\":"+eligible.get()+"}"
          : "{\"id\":\"business-user\",\"phone\":\"13800009009\",\"nickname\":\"真实用户\",\"enabled\":"+enabled.get()+",\"authVersion\":"+userVersion.get()+"}";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    });
    upstream.start();
    String url = "http://127.0.0.1:" + upstream.getAddress().getPort();
    try (var db = new ReleaseAuthMysqlTest.Database(); var app = ReleaseAuthMysqlTest.start(db, "prod", Map.of(
        "iam.user-service-url", url, "iam.portal-access-service-url", url,
        "iam.portal-promotion-service-url", url, "iam.portal-payment-service-url", url, "iam.portal-internal-token", "portal-test-only-token-0000000000000000"))) {
      var primary = new ReleaseAuthMysqlTest.Browser(app);
      assertEquals(200, primary.post("/api/v1/auth/admin/login", ReleaseAuthMysqlTest.credentials(), primary.csrf()).statusCode());
      String primaryId = primary.session().path("user").path("id").asText();
      var managers = new java.util.ArrayList<ReleaseAuthMysqlTest.Browser>();
      for (String managerRole : new String[]{"NORMAL_ADMIN", "ADMIN"}) {
        String name = "manager."+managerRole;
        assertEquals(201,primary.post("/api/v1/admin/admins",Map.of("username",name,"password","ManagerPassword!2026","role",managerRole),primary.csrf()).statusCode());
        var manager = new ReleaseAuthMysqlTest.Browser(app);
        assertEquals(200,manager.post("/api/v1/auth/admin/login",Map.of("username",name,"password","ManagerPassword!2026"),manager.csrf()).statusCode());
        managers.add(manager);
      }
      // Unbound users may enter, but administrators cannot create API credentials before payment.
      assertEquals(400, primary.post("/api/v1/admin/admins", Map.of("username", "unpaid.api", "password", "RolePassword!2026", "role", "API_USER", "boundUserId", "business-user"), primary.csrf()).statusCode());
      var selfSource=userBrowser(app,"business-user",userVersion.get());
      paymentBound.set(true);eligible.set(false);
      assertFalse(selfSource.send("GET","/api/v1/auth/portal/options",Map.of(),null).body().contains("API_USER"), "Payment PERSONAL without Access key cannot enter");
      paymentBound.set(false);eligible.set(true);
      assertTrue(selfSource.send("GET","/api/v1/auth/portal/options",Map.of(),null).body().contains("API_USER"));
      assertFalse(selfSource.send("GET","/api/v1/auth/portal/options",Map.of(),null).body().contains("PROMOTER"));
      String selfTicket=ticket(selfSource,"API_USER");
      var selfDestination=new ReleaseAuthMysqlTest.Browser(app);
      assertEquals(200,redeemStatus(selfDestination,selfTicket,selfDestination.csrf()));
      assertEquals("api_user",selfDestination.session().path("roles").get(0).asText());
      assertEquals("business-user",selfDestination.session().path("user").path("id").asText());
      assertFalse(selfDestination.session().path("primary").asBoolean());
      assertEquals(403,selfDestination.send("GET","/api/v1/admin/admins",Map.of(),null).statusCode());
      assertEquals(403,selfDestination.post("/api/v1/auth/admin/password",Map.of("currentPassword","UnusedPassword!2026","newPassword","ChangedPassword!2026"),selfDestination.csrf()).statusCode());
      assertFalse(primary.send("GET","/api/v1/admin/admins",Map.of(),null).body().contains("business-user"));
      accountBound.set(true);paymentBound.set(true);
      assertTrue(selfDestination.session().path("authenticated").asBoolean(), "payment binding preserves the API session");
      // API business users need no separate administrator-managed login credential.
      String apiEntryPath="/api/v1/admin/api-users/business-user/agent-ticket";
      for(var manager:managers) assertEquals(403,manager.post(apiEntryPath,Map.of("challenge",CHALLENGE),manager.csrf()).statusCode());
      assertEquals(403,primary.post(apiEntryPath,Map.of("challenge",CHALLENGE),null).statusCode());
      assertEquals(403,selfSource.post(apiEntryPath,Map.of("challenge",CHALLENGE),selfSource.csrf()).statusCode());
      String sourceCookie=primary.cookie(),sourceCsrf=primary.csrf();
      String apiTicket=agentTicket(primary,apiEntryPath);
      var apiDestination=new ReleaseAuthMysqlTest.Browser(app);
      assertEquals(403,apiDestination.post("/api/v1/auth/portal/login",Map.of("ticket",apiTicket,"verifier","b".repeat(64)),apiDestination.csrf()).statusCode());
      assertEquals(200,redeemStatus(apiDestination,apiTicket,apiDestination.csrf()));
      assertEquals(403,redeemStatus(apiDestination,apiTicket,apiDestination.csrf()));
      assertEquals("business-user",apiDestination.session().path("user").path("id").asText());
      assertEquals("api_user",apiDestination.session().path("roles").get(0).asText());
      assertEquals(403,apiDestination.send("GET","/api/v1/admin/admins",Map.of(),null).statusCode());
      assertEquals(sourceCookie,primary.cookie());assertEquals(sourceCsrf,primary.csrf());
      assertEquals(0,db.jdbc.queryForObject("SELECT COUNT(*) FROM iam_admin WHERE role='API_USER'",Integer.class));
      assertEquals(1,db.jdbc.queryForObject("SELECT COUNT(*) FROM agent_entry_audit WHERE actor_id=? AND credential_id=? AND user_id=?",Integer.class,primaryId,"business-user","business-user"));
      enabled.set(false);assertEquals(403,primary.post(apiEntryPath,Map.of("challenge",CHALLENGE),primary.csrf()).statusCode());enabled.set(true);
      eligible.set(false);assertEquals(403,primary.post(apiEntryPath,Map.of("challenge",CHALLENGE),primary.csrf()).statusCode());eligible.set(true);
      accountBound.set(false);assertEquals(403,primary.post(apiEntryPath,Map.of("challenge",CHALLENGE),primary.csrf()).statusCode());accountBound.set(true);
      eligible.set(false);
      assertFalse(selfDestination.session().path("authenticated").asBoolean());
      assertFalse(selfSource.send("GET","/api/v1/auth/portal/options",Map.of(),null).body().contains("API_USER"));
      assertEquals(403,selfSource.post("/api/v1/auth/portal/tickets",Map.of("role","API_USER","challenge",CHALLENGE),selfSource.csrf()).statusCode());
      eligible.set(true);
      assertFalse(selfDestination.session().path("authenticated").asBoolean());
      for (String role : new String[]{"API_USER", "PROMOTER"}) {
        String username = role.equals("PROMOTER") ? "测试代理" : role.toLowerCase()+".real", password = "RolePassword!2026";
        var credentials = Map.<String,Object>of("username", username, "password", password);
        for(String invalid : new String[]{"", "测试 代理", "测试\n代理", "<代理>", "测".repeat(65)})
          assertEquals(400,primary.post("/api/v1/admin/admins",Map.of("username",invalid,"password",password,"role",role,"boundUserId","business-user"),primary.csrf()).statusCode());
        assertEquals(400, primary.post("/api/v1/admin/admins", Map.of("username", username, "password", password, "role", role), primary.csrf()).statusCode());
        eligible.set(false);
        assertEquals(400, primary.post("/api/v1/admin/admins", Map.of("username", username, "password", password, "role", role,"boundUserId","business-user"), primary.csrf()).statusCode());
        eligible.set(true);
        var created = primary.post("/api/v1/admin/admins", Map.of("username", username, "password", password, "role", role,"boundUserId","business-user"), primary.csrf());
        assertEquals(201, created.statusCode(), created.body());
        String credentialId = ReleaseAuthMysqlTest.JSON.readTree(created.body()).path("id").asText();
        assertNotEquals("business-user",credentialId);
        String entryPath="/api/v1/admin/admins/"+credentialId+"/agent-ticket";
        for(var manager:managers) assertEquals(403,manager.post(entryPath,Map.of("challenge",CHALLENGE),manager.csrf()).statusCode());
        assertEquals(403,primary.post(entryPath,Map.of("challenge",CHALLENGE),null).statusCode());
        {
          assertEquals(403,primary.post("/api/v1/admin/admins/"+primaryId+"/agent-ticket",Map.of("challenge",CHALLENGE),primary.csrf()).statusCode());
          assertEquals(404,primary.post("/api/v1/admin/admins/missing/agent-ticket",Map.of("challenge",CHALLENGE),primary.csrf()).statusCode());
          enabled.set(false);assertEquals(403,primary.post(entryPath,Map.of("challenge",CHALLENGE),primary.csrf()).statusCode());enabled.set(true);
          eligible.set(false);assertEquals(403,primary.post(entryPath,Map.of("challenge",CHALLENGE),primary.csrf()).statusCode());eligible.set(true);
          primary.send("PATCH","/api/v1/admin/admins/"+credentialId+"/status",Map.of("enabled",false),primary.csrf());
          assertEquals(403,primary.post(entryPath,Map.of("challenge",CHALLENGE),primary.csrf()).statusCode());
          primary.send("PATCH","/api/v1/admin/admins/"+credentialId+"/status",Map.of("enabled",true),primary.csrf());
          var entry=new ReleaseAuthMysqlTest.Browser(app);
          assertEquals(200,entry.post("/api/v1/auth/admin/login",ReleaseAuthMysqlTest.credentials(),entry.csrf()).statusCode());
          String oldCsrf=entry.csrf(),oldCookie=entry.cookie();
          String first=agentTicket(entry,entryPath),second=agentTicket(entry,entryPath);
          assertEquals(oldCsrf,entry.csrf());assertEquals(oldCookie,entry.cookie());
          assertEquals("admin",entry.session().path("roles").get(0).asText());
          var agentA=new ReleaseAuthMysqlTest.Browser(app);var agentB=new ReleaseAuthMysqlTest.Browser(app);
          assertEquals(403,agentA.post("/api/v1/auth/portal/login",Map.of("ticket",first,"verifier","b".repeat(64)),agentA.csrf()).statusCode());
          assertEquals(200,redeemStatus(agentA,first,agentA.csrf()));
          assertEquals(200,redeemStatus(agentB,second,agentB.csrf()));
          assertEquals(403,redeemStatus(agentB,second,agentB.csrf()));
          assertEquals(role.toLowerCase(),agentA.session().path("roles").get(0).asText());
          assertEquals("business-user",agentA.session().path("user").path("id").asText());
          assertEquals(403,agentA.send("GET","/api/v1/admin/admins",Map.of(),null).statusCode());
          assertEquals(200,entry.send("GET","/api/v1/admin/admins",Map.of(),null).statusCode());
          assertEquals(oldCookie,entry.cookie());assertEquals(oldCsrf,entry.csrf());
          assertEquals(2,db.jdbc.queryForObject("SELECT COUNT(*) FROM agent_entry_audit WHERE actor_id=? AND credential_id=? AND user_id=?",Integer.class,primaryId,credentialId,"business-user"));
          String stale=agentTicket(entry,entryPath);eligible.set(false);assertEquals(403,redeemStatus(agentB,stale,agentB.csrf()));eligible.set(true);
          String expiredAgent=agentTicket(entry,entryPath);db.jdbc.update("UPDATE portal_handoff SET expires_at=0");assertEquals(403,redeemStatus(agentB,expiredAgent,agentB.csrf()));
          assertEquals(200,agentA.post("/api/v1/auth/logout",Map.of(),agentA.csrf()).statusCode());
          assertEquals("admin",entry.session().path("roles").get(0).asText());
          String revokedSource=agentTicket(entry,entryPath);
          assertEquals(200,entry.post("/api/v1/auth/logout",Map.of(),entry.csrf()).statusCode());
          assertEquals(403,redeemStatus(agentB,revokedSource,agentB.csrf()));
        }
        var userBrowser = userBrowser(app, "business-user", userVersion.get());
        String userCookie = userBrowser.cookie();
        assertTrue(userBrowser.send("GET", "/api/v1/auth/portal/options", Map.of(), null).body().contains(role));
        assertEquals(403, primary.post("/api/v1/auth/portal/tickets", Map.of("role", role, "challenge", CHALLENGE), primary.csrf()).statusCode());
        assertEquals(403, userBrowser.post("/api/v1/auth/portal/tickets", Map.of("role", role, "challenge", CHALLENGE), null).statusCode());
        assertEquals(403, userBrowser.post("/api/v1/auth/portal/tickets", Map.of("role", "ADMIN"), userBrowser.csrf()).statusCode());
        String ticket = ticket(userBrowser, role);
        assertFalse(db.jdbc.queryForList("SELECT * FROM portal_handoff").toString().contains(ticket));
        assertEquals(403,userBrowser.post("/api/v1/auth/portal/login",Map.of("ticket",ticket,"verifier",VERIFIER),userBrowser.csrf()).statusCode());
        assertEquals("user",userBrowser.session().path("roles").get(0).asText());
        var destination = new ReleaseAuthMysqlTest.Browser(app);
        assertEquals(403, destination.post("/api/v1/auth/portal/login", Map.of("ticket", ticket, "verifier", VERIFIER), null).statusCode());
        var attacker = new ReleaseAuthMysqlTest.Browser(app);
        assertEquals(403, attacker.post("/api/v1/auth/portal/login", Map.of("ticket", ticket,"verifier","b".repeat(64)),attacker.csrf()).statusCode());
        String beforeHandoff = destination.csrf();
        assertEquals(200, destination.post("/api/v1/auth/portal/login", Map.of("ticket", ticket, "verifier", VERIFIER), beforeHandoff).statusCode());
        assertNotEquals(beforeHandoff, destination.csrf());
        assertEquals(role.toLowerCase(), destination.session().path("roles").get(0).asText());
        assertEquals("business-user", destination.session().path("user").path("id").asText());
        assertEquals(403, destination.send("GET", "/api/v1/admin/admins", Map.of(), null).statusCode());
        assertEquals(userCookie, userBrowser.cookie());
        assertEquals("user", userBrowser.session().path("roles").get(0).asText());
        assertEquals(403, destination.post("/api/v1/auth/portal/login", Map.of("ticket", ticket, "verifier", VERIFIER), destination.csrf()).statusCode());
        String expired = ticket(userBrowser, role);
        db.jdbc.update("UPDATE portal_handoff SET expires_at=0");
        assertEquals(403, destination.post("/api/v1/auth/portal/login", Map.of("ticket", expired, "verifier", VERIFIER), destination.csrf()).statusCode());
        String ineligible = ticket(userBrowser, role);
        eligible.set(false);
        assertEquals(403, destination.post("/api/v1/auth/portal/login", Map.of("ticket", ineligible, "verifier", VERIFIER), destination.csrf()).statusCode());
        eligible.set(true);
        String raceTicket=ticket(userBrowser,role);
        var peerA=new ReleaseAuthMysqlTest.Browser(app); var peerB=new ReleaseAuthMysqlTest.Browser(app);
        String csrfA=peerA.csrf(),csrfB=peerB.csrf();
        var callA=java.util.concurrent.CompletableFuture.supplyAsync(()->redeemStatus(peerA,raceTicket,csrfA));
        var callB=java.util.concurrent.CompletableFuture.supplyAsync(()->redeemStatus(peerB,raceTicket,csrfB));
        assertEquals(java.util.List.of(200,403),java.util.stream.Stream.of(callA.get(),callB.get()).sorted().toList());
        String revoked=ticket(userBrowser,role);
        assertEquals(200,primary.send("PATCH","/api/v1/admin/admins/"+credentialId+"/status",Map.of("enabled",false),primary.csrf()).statusCode());
        assertEquals(role.equals("API_USER")?200:403,destination.post("/api/v1/auth/portal/login",Map.of("ticket",revoked,"verifier",VERIFIER),destination.csrf()).statusCode());
        assertEquals(200,primary.send("PATCH","/api/v1/admin/admins/"+credentialId+"/status",Map.of("enabled",true),primary.csrf()).statusCode());
        // Credential-bound tickets cannot revive; API self-entry tickets remain single-use.
        assertEquals(403,destination.post("/api/v1/auth/portal/login",Map.of("ticket",revoked,"verifier",VERIFIER),destination.csrf()).statusCode());
        String loggedOut = ticket(userBrowser, role);
        assertEquals(200, userBrowser.post("/api/v1/auth/logout", Map.of(), userBrowser.csrf()).statusCode());
        assertEquals(403, destination.post("/api/v1/auth/portal/login", Map.of("ticket", loggedOut, "verifier", VERIFIER), destination.csrf()).statusCode());
        for(var manager:managers) {
          assertEquals(403,manager.post("/api/v1/admin/admins/"+credentialId+"/password",Map.of("password","ForbiddenPassword!2026"),manager.csrf()).statusCode());
          assertEquals(403,manager.send("PATCH","/api/v1/admin/admins/"+credentialId+"/status",Map.of("enabled",false),manager.csrf()).statusCode());
          assertFalse(manager.send("GET","/api/v1/admin/admins",Map.of(),null).body().contains(username));
        }
        var browser = new ReleaseAuthMysqlTest.Browser(app);
        assertEquals(401,browser.post("/api/v1/auth/admin/login",Map.of("username",username,"password","WrongPassword!"),browser.csrf()).statusCode());
        String before=browser.cookie();
        assertEquals(200,browser.post("/api/v1/auth/admin/login",credentials,browser.csrf()).statusCode());
        assertNotEquals(before,browser.cookie());
        var session=browser.session();
        assertEquals("business-user", session.path("user").path("id").asText());
        assertEquals(role.toLowerCase(),session.path("roles").get(0).asText());
        assertFalse(session.path("primary").asBoolean());
        assertEquals(403,browser.send("GET","/api/v1/admin/admins",Map.of(),null).statusCode());
        assertEquals(403,browser.post("/api/v1/admin/admins/"+primaryId+"/password",Map.of("password",password),browser.csrf()).statusCode());
        assertEquals(403,primary.send("PATCH","/api/v1/admin/admins/"+credentialId+"/role",Map.of("role","ADMIN"),primary.csrf()).statusCode());
        eligible.set(false);
        assertFalse(browser.session().path("authenticated").asBoolean());
        eligible.set(true);
        assertFalse(browser.session().path("authenticated").asBoolean());
        assertEquals(200,browser.post("/api/v1/auth/admin/login",credentials,browser.csrf()).statusCode());
        userVersion.incrementAndGet();
        assertFalse(browser.session().path("authenticated").asBoolean());
        assertEquals(200,browser.post("/api/v1/auth/admin/login",credentials,browser.csrf()).statusCode());
        enabled.set(false);
        assertFalse(browser.session().path("authenticated").asBoolean());
        enabled.set(true);
        assertEquals(200,browser.post("/api/v1/auth/admin/login",credentials,browser.csrf()).statusCode());
        assertEquals(400,browser.post("/api/v1/auth/admin/password",Map.of("currentPassword","WrongPassword2026","newPassword","ChangedPassword!2026"),browser.csrf()).statusCode());
        assertTrue(browser.session().path("authenticated").asBoolean());
        assertEquals(200,browser.post("/api/v1/auth/admin/password",Map.of("currentPassword",password,"newPassword","ChangedPassword!2026"),browser.csrf()).statusCode());
        assertFalse(browser.session().path("authenticated").asBoolean());
        assertEquals(200,browser.post("/api/v1/auth/admin/login",Map.of("username",username,"password","ChangedPassword!2026"),browser.csrf()).statusCode());
        assertEquals(200,primary.send("PATCH","/api/v1/admin/admins/"+credentialId+"/status",Map.of("enabled",false),primary.csrf()).statusCode());
        assertFalse(browser.session().path("authenticated").asBoolean());
      }
      assertTrue(invalidRequests.isEmpty(), invalidRequests.toString());
    } finally { upstream.stop(0); }
  }
  private static int redeemStatus(ReleaseAuthMysqlTest.Browser browser,String ticket,String csrf) {
    try { return browser.post("/api/v1/auth/portal/login",Map.of("ticket",ticket,"verifier",VERIFIER),csrf).statusCode(); }
    catch(Exception failure) { throw new RuntimeException(failure); }
  }
  private static String agentTicket(ReleaseAuthMysqlTest.Browser source,String path) throws Exception {
    var response=source.post(path,Map.of("challenge",CHALLENGE),source.csrf());
    assertEquals(200,response.statusCode(),response.body());
    return ReleaseAuthMysqlTest.JSON.readTree(response.body()).path("ticket").asText();
  }
  private static String ticket(ReleaseAuthMysqlTest.Browser user, String role) throws Exception {
    var response = user.post("/api/v1/auth/portal/tickets", Map.of("role", role, "challenge", CHALLENGE), user.csrf());
    assertEquals(200, response.statusCode(), response.body());
    return ReleaseAuthMysqlTest.JSON.readTree(response.body()).path("ticket").asText();
  }
  private static ReleaseAuthMysqlTest.Browser userBrowser(org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext app, String userId, long version) {
    org.springframework.session.SessionRepository<org.springframework.session.Session> repository = (org.springframework.session.SessionRepository) app.getBean(org.springframework.session.jdbc.JdbcIndexedSessionRepository.class);
    var session = repository.createSession();
    var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
    context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
        new com.xzwl.cloud.iam.application.account.AuthPrincipal(userId,"真实用户","USER",false,version), null,
        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
    session.setAttribute("SPRING_SECURITY_CONTEXT", context);
    session.setAttribute(com.xzwl.cloud.iam.interfaces.http.SessionLogin.AUTHENTICATED_AT, System.currentTimeMillis());
    repository.save(session);
    var browser = new ReleaseAuthMysqlTest.Browser(app);
    var cookie = new java.net.HttpCookie("XZWL_SESSION", java.util.Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    cookie.setPath("/"); cookie.setVersion(0);
    browser.cookies.getCookieStore().add(java.net.URI.create(browser.base), cookie);
    return browser;
  }

}
