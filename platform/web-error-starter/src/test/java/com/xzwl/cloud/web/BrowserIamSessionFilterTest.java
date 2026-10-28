package com.xzwl.cloud.web;

import static org.assertj.core.api.Assertions.assertThat;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import jakarta.servlet.http.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.*;

class BrowserIamSessionFilterTest {
  HttpServer iam;
  AtomicReference<String> roles = new AtomicReference<>("[\"user\"]");
  AtomicBoolean primary = new AtomicBoolean(false);
  AtomicInteger status = new AtomicInteger(200);
  AtomicReference<String> seen = new AtomicReference<>();
  @BeforeEach void start() throws Exception {
    iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    iam.createContext("/internal/v1/iam/", exchange -> {
      seen.set(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + exchange.getRequestHeaders().getFirst("Cookie") + " " + exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"));
      byte[] body = ("{\"authenticated\":true,\"roles\":" + roles.get() + ",\"primary\":" + primary.get() + ",\"user\":{\"id\":\"verified-user\"}}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status.get(), body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    iam.start();
  }
  @AfterEach void stop() { iam.stop(0); }
  ApplicationContextRunner context() {
    return new ApplicationContextRunner().withUserConfiguration(WebErrorAutoConfiguration.class)
        .withPropertyValues("xzwl.iam-session.enabled=true", "xzwl.iam-session.base-url=http://127.0.0.1:" + iam.getAddress().getPort());
  }
  @Test void optInFilterUsesIamIdentityAndCsrfInsteadOfCallerHeaders() {
    context().run(ctx -> {
      assertThat(ctx).hasBean("browserIamSessionFilter");
      Filter filter = (Filter) ctx.getBean("browserIamSessionFilter");
      var request = request("GET", "/api/v1/promotions/applications/id", true);
      var response = new MockHttpServletResponse();
      AtomicBoolean called = new AtomicBoolean();
      filter.doFilter(request, response, (req, res) -> {
        called.set(true); var authenticated = (HttpServletRequest) req;
        assertThat(authenticated.getUserPrincipal().getName()).isEqualTo("verified-user");
        assertThat(authenticated.isUserInRole("USER")).isTrue();
        assertThat(authenticated.isUserInRole("ADMIN")).isFalse();
      });
      assertThat(called).isTrue();
      assertThat(seen.get()).isEqualTo("GET /internal/v1/iam/session XZWL_SESSION=valid null");
      roles.set("[\"admin\"]");
      request = request("POST", "/api/v1/promotions/applications", true);
      request.addHeader("X-CSRF-TOKEN", "real-token");
      filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> assertThat(((HttpServletRequest)req).isUserInRole("ADMIN")).isTrue());
      assertThat(seen.get()).isEqualTo("POST /internal/v1/iam/session/verify XZWL_SESSION=valid real-token");
    });
  }
  @Test void primaryRoleComesOnlyFromVerifiedIamFlag() {
    context().run(ctx->{
      Filter filter=(Filter)ctx.getBean("browserIamSessionFilter");
      roles.set("[\"admin\"]");
      for(boolean expected:new boolean[]{false,true}) {
        primary.set(expected);
        var request=request("GET","/api/v1/admin/routing/providers",true);
        request.addUserRole("PRIMARY_ADMIN");request.addHeader("X-Primary-Admin","true");
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->
            assertThat(((HttpServletRequest)req).isUserInRole("PRIMARY_ADMIN")).isEqualTo(expected));
      }
      roles.set("[\"normal_admin\"]");
      filter.doFilter(request("GET","/api/v1/admin/routing/providers",true),new MockHttpServletResponse(),(req,res)->
          assertThat(((HttpServletRequest)req).isUserInRole("PRIMARY_ADMIN")).isFalse());
    });
  }
  @Test void deniesMissingCookieMissingCsrfExpiredSessionAndIamOutage() {
    context().run(ctx -> {
      assertThat(ctx).hasBean("browserIamSessionFilter");
      Filter filter = (Filter) ctx.getBean("browserIamSessionFilter");
      assertDenied(filter, request("GET", "/api/v1/files/id/access-url", false), 401);
      assertDenied(filter, request("POST", "/api/v1/files/avatars", true), 403);
      status.set(401); assertDenied(filter, request("GET", "/api/v1/admin/operations/users", true), 401);
      status.set(403); assertDenied(filter, request("GET", "/api/v1/admin/operations/users", true), 403);
      status.set(500); assertDenied(filter, request("GET", "/api/v1/admin/operations/users", true), 503);
      status.set(200); roles.set("[\"service\"]"); assertDenied(filter, request("GET", "/api/v1/admin/operations/users", true), 403);
      iam.stop(0); assertDenied(filter, request("GET", "/api/v1/admin/operations/users", true), 503);
    });
  }
  @Test void optInDoesNotAuthenticateInternalServiceEndpoints() {
    context().run(ctx -> {
      assertThat(ctx).hasBean("browserIamSessionFilter");
      Filter filter = (Filter) ctx.getBean("browserIamSessionFilter");
      for (String path : List.of("/internal/v1/files/media-results/archive-url", "/api/v1/internal/promotions/commissions/recharges")) {
        AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(request("POST", path, false), new MockHttpServletResponse(), (req,res) -> called.set(true));
        assertThat(called).isTrue(); assertThat(seen.get()).isNull();
      }
    });
  }
  @Test void portalRolesCannotEnterUnrelatedServicesOrAcquireUserAuthority() {
    context().run(ctx -> {
      Filter filter = (Filter) ctx.getBean("browserIamSessionFilter");
      roles.set("[\"promoter\"]");
      AtomicBoolean called = new AtomicBoolean();
      filter.doFilter(request("GET", "/api/v1/promotions/portal/overview", true), new MockHttpServletResponse(), (req,res) -> {
        called.set(true);
        assertThat(((HttpServletRequest)req).isUserInRole("PROMOTER")).isTrue();
        assertThat(((HttpServletRequest)req).isUserInRole("USER")).isFalse();
      });
      assertThat(called).isTrue();
      for (String path : List.of("/api/v1/media/tasks", "/api/v1/canvases", "/api/v1/promotions/referrals/other", "/api/v1/admin/promotions/applications"))
        assertDenied(filter, request("GET", path, true), 403);
      roles.set("[\"api_user\"]");
      assertDenied(filter, request("GET", "/api/v1/promotions/portal/overview", true), 403);
      assertDenied(filter, request("GET", "/api/v1/media/tasks", true), 403);
    });
  }
  static MockHttpServletRequest request(String method, String path, boolean cookie) {
    var request = new MockHttpServletRequest(method, path);
    if (cookie) request.setCookies(new Cookie("XZWL_SESSION", "valid"));
    request.addHeader("X-User-Id", "forged-admin"); request.addHeader("X-Role", "ADMIN");
    return request;
  }
  static void assertDenied(Filter filter, MockHttpServletRequest request, int status) throws Exception {
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req,res) -> { throw new AssertionError("Denied request reached controller"); });
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(response.getContentAsString()).contains("\"code\"");
  }
}
