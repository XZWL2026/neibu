package com.xzwl.cloud.web;

import static org.assertj.core.api.Assertions.assertThat;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;

class PlatformApiKeyFilterTest {
  HttpServer access;
  AtomicInteger status = new AtomicInteger(200);
  AtomicReference<String> body = new AtomicReference<>("{\"ownerId\":\"owner\",\"accountType\":\"API\",\"kind\":\"API\",\"enabled\":true}");
  AtomicReference<String> seen = new AtomicReference<>();
  PlatformApiKeyFilter filter;
  @BeforeEach void start() throws Exception {
    access=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    access.createContext("/internal/v1/access/authorize",exchange->{
      seen.set(exchange.getRequestHeaders().getFirst("X-Service-Token")+":"+new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
      byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");
      exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
    });
    access.start(); filter=new PlatformApiKeyFilter("http://127.0.0.1:"+access.getAddress().getPort(),"service-token");
  }
  @AfterEach void stop(){access.stop(0);}
  @Test void bearerAuthenticatesOwnerWithoutCookieOrCsrfAndStorageBridgePreservesIt() throws Exception {
    var request=request("POST","/api/v1/files/reference-images","Bearer sk-_"+"a".repeat(64));
    request.addHeader("X-User-Id","forged");request.addUserRole("ADMIN");
    AtomicBoolean reached=new AtomicBoolean();
    filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{
      new BrowserIamSessionFilter("http://127.0.0.1:1").doFilter(req,res,(verified,out)->{
        reached.set(true);var http=(HttpServletRequest)verified;
        assertThat(http.getUserPrincipal().getName()).isEqualTo("owner");
        assertThat(http.isUserInRole("USER")).isTrue();
        assertThat(http.isUserInRole("ADMIN")).isFalse();
      });
    });
    assertThat(reached).isTrue();assertThat(seen.get()).contains("service-token:","\"capability\":\"API\"");
  }
  @Test void invalidDisabledWrongKindOrUnavailableNeverFallBackToCookie() throws Exception {
    denied("Bearer nope",401);
    status.set(403);denied("Bearer sk-_"+"a".repeat(64),401);
    status.set(500);denied("Bearer sk-_"+"a".repeat(64),503);
    status.set(200);body.set("{\"ownerId\":\"owner\",\"accountType\":\"API\",\"kind\":\"API\",\"enabled\":false}");denied("Bearer sk-_"+"a".repeat(64),401);
    body.set("{\"ownerId\":\"owner\",\"accountType\":\"PERSONAL\",\"kind\":\"MEDIA\",\"enabled\":true}");denied("Bearer sk-_"+"a".repeat(64),401);
  }
  @Test void onlyDocumentedMethodPathsAcceptApiKeys() throws Exception {
    for(String path:new String[]{"/api/v1/admin/media/tasks","/internal/v1/files/media-results","/api/v1/files/avatars","/api/v1/media/unknown"}) {
      filter.doFilter(request("POST",path,"Bearer sk-_"+"a".repeat(64)),new MockHttpServletResponse(),(req,res)->assertThat(((HttpServletRequest)req).getUserPrincipal()).isNull());
      assertThat(seen.get()).isNull();
    }
    var duplicate=request("POST","/api/v1/media/tasks","Bearer sk-_"+"a".repeat(64));duplicate.addHeader("Authorization","Bearer other");
    var response=new MockHttpServletResponse();filter.doFilter(duplicate,response,(req,res)->{throw new AssertionError();});assertThat(response.getStatus()).isEqualTo(401);
  }
  @Test void noAuthorizationLeavesBrowserAuthenticationInCharge() throws Exception {
    var request=new MockHttpServletRequest("POST","/api/v1/media/tasks");
    filter.doFilter(request,new MockHttpServletResponse(),(req,res)->assertThat(req).isSameAs(request));assertThat(seen.get()).isNull();
  }
  @Test void portraitReviewMethodsAuthenticateTheOwnerWithoutOpeningOtherActions() throws Exception {
    for (String[] endpoint : new String[][]{{"POST","/api/v1/media/portraits"},
        {"GET","/api/v1/media/portraits"},{"GET","/api/v1/media/portraits/portrait-123"}}) {
      AtomicBoolean reached=new AtomicBoolean();
      filter.doFilter(request(endpoint[0],endpoint[1],"Bearer sk-_"+"a".repeat(64)),new MockHttpServletResponse(),(req,res)->{
        reached.set(true);
        assertThat(((HttpServletRequest)req).getUserPrincipal().getName()).isEqualTo("owner");
      });
      assertThat(reached).isTrue();
    }
    assertThat(PlatformApiKeyFilter.accepts("DELETE","/api/v1/media/portraits/portrait-123")).isFalse();
    assertThat(PlatformApiKeyFilter.accepts("POST","/api/v1/media/portraits/portrait-123")).isFalse();
    assertThat(PlatformApiKeyFilter.accepts("GET","/api/v1/media/portraits/portrait-123/secret")).isFalse();
  }
  void denied(String authorization,int expected) throws Exception {
    var response=new MockHttpServletResponse();filter.doFilter(request("POST","/api/v1/media/tasks",authorization),response,(req,res)->{throw new AssertionError();});
    assertThat(response.getStatus()).isEqualTo(expected);assertThat(response.getContentAsString()).doesNotContain("sk-_");
  }
  MockHttpServletRequest request(String method,String path,String auth) {
    var req=new MockHttpServletRequest(method,path);req.addHeader("Authorization",auth);req.setCookies(new jakarta.servlet.http.Cookie("XZWL_SESSION","valid"));return req;
  }
}
