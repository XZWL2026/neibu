package com.xzwl.cloud.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalDiscoveryHttpTest {
  @Test void discoveredServicesPreserveBrowserAndApiCredentials() throws Exception { exercise(true); }
  @Test void defaultLocalClientsUseFixedUrls() throws Exception { exercise(false); }

  @Test void missingInstancesKeepTheExistingUnavailableResponse() {
    new ApplicationContextRunner().withUserConfiguration(TestApplication.class)
        .withPropertyValues("xzwl.discovery.enabled=true", "spring.cloud.loadbalancer.retry.enabled=false",
            "xzwl.iam-session.enabled=true", "xzwl.iam-session.base-url=http://unregistered-service")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var request = new MockHttpServletRequest("GET", "/api/v1/canvas/documents");
          request.setCookies(new Cookie("XZWL_SESSION", "session-123"));
          var response = new MockHttpServletResponse();
          assertThatCode(() -> context.getBean(BrowserIamSessionFilter.class).doFilter(request, response,
              (accepted, output) -> { throw new AssertionError("Unregistered service cannot authorize a session"); }))
              .doesNotThrowAnyException();
          assertThat(response.getStatus()).isEqualTo(503);
          assertThat(response.getContentAsString()).contains("IAM_UNAVAILABLE");
        });
  }

  private void exercise(boolean discovery) throws Exception {
    var requests = new AtomicInteger();
    var upstreamStatus = new AtomicInteger(200);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requests.incrementAndGet();
      String response;
      if (exchange.getRequestURI().getPath().equals("/internal/v1/iam/session/verify")) {
        assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isEqualTo("XZWL_SESSION=session-123");
        assertThat(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN")).isEqualTo("csrf-123");
        assertThat(exchange.getRequestHeaders().getFirst("X-Service-Token")).isNull();
        response = "{\"authenticated\":true,\"roles\":[\"user\"],\"user\":{\"id\":\"u1\"}}";
      } else {
        assertThat(exchange.getRequestURI().getPath()).isEqualTo("/internal/v1/access/authorize");
        assertThat(exchange.getRequestHeaders().getFirst("X-Service-Token")).isEqualTo("internal-token");
        assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
        assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
            .contains("sk-" + "a".repeat(32));
        response = "{\"ownerId\":\"u1\",\"accountType\":\"API\",\"kind\":\"API\",\"enabled\":true}";
      }
      byte[] body = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(upstreamStatus.get(), body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    String fixedUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    try {
      var runner = new ApplicationContextRunner().withUserConfiguration(TestApplication.class)
          .withPropertyValues("spring.cloud.loadbalancer.retry.enabled=false", "spring.cloud.loadbalancer.cache.enabled=false",
              "xzwl.iam-session.enabled=true", "xzwl.api-key.enabled=true", "xzwl.api-key.service-token=internal-token",
              "xzwl.iam-session.base-url=" + (discovery ? "http://iam-service" : fixedUrl),
              "xzwl.api-key.access-url=" + (discovery ? "http://access-service" : fixedUrl),
              "spring.cloud.discovery.client.simple.instances.iam-service[0].uri=" + fixedUrl,
              "spring.cloud.discovery.client.simple.instances.access-service[0].uri=" + fixedUrl);
      if (discovery) runner = runner.withPropertyValues("xzwl.discovery.enabled=true");
      runner.run(context -> {
        assertThat(context).hasNotFailed();
        var browser = new MockHttpServletRequest("POST", "/api/v1/canvas/documents");
        browser.setCookies(new Cookie("XZWL_SESSION", "session-123"));
        browser.addHeader("X-CSRF-TOKEN", "csrf-123");
        var browserResponse = new MockHttpServletResponse();
        var accepted = new AtomicInteger();
        context.getBean(BrowserIamSessionFilter.class).doFilter(browser, browserResponse, (request, response) -> accepted.incrementAndGet());
        assertThat(browserResponse.getStatus()).isEqualTo(200);
        assertThat(accepted).hasValue(1);
        var api = new MockHttpServletRequest("POST", "/api/v1/media/tasks");
        api.addHeader("Authorization", "Bearer sk-" + "a".repeat(32));
        var apiResponse = new MockHttpServletResponse();
        context.getBean(PlatformApiKeyFilter.class).doFilter(api, apiResponse, (request, response) -> accepted.incrementAndGet());
        assertThat(apiResponse.getStatus()).isEqualTo(200);
        assertThat(accepted).hasValue(2);
        assertThat(requests).hasValue(2);
        upstreamStatus.set(503);
        var unavailable = new MockHttpServletResponse();
        context.getBean(PlatformApiKeyFilter.class).doFilter(api, unavailable, (request, response) -> accepted.incrementAndGet());
        assertThat(unavailable.getStatus()).isEqualTo(503);
        assertThat(accepted).hasValue(2);
        assertThat(requests).hasValue(3); // One failed write, no extra delivery attempt.
      });
    } finally { server.stop(0); }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class TestApplication {}
}
