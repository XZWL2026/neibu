package com.xzwl.cloud.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.main.web-application-type=reactive", "spring.cloud.nacos.discovery.enabled=false",
    "spring.cloud.nacos.config.enabled=false"
})
class GatewayUnavailableTest {
  static final HttpServer BROKEN = brokenServer();
  static HttpServer brokenServer() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> exchange.close());
      server.start(); return server;
    } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
  }
  @DynamicPropertySource static void upstream(DynamicPropertyRegistry properties) {
    properties.add("gateway.upstreams.iam", () -> "http://127.0.0.1:" + BROKEN.getAddress().getPort());
  }
  @AfterAll static void stop() { BROKEN.stop(0); }
  @LocalServerPort int port;
  @Test void connectionFailureReturnsCorrelatedBadGatewayInsteadOfHtmlOrAStackTrace() {
    WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build().get()
        .uri("/api/v1/auth/session").header("X-Request-Id", "connection-failure").exchange()
        .expectStatus().isEqualTo(502).expectHeader().contentType("application/json")
        .expectBody().jsonPath("$.code").isEqualTo("BAD_GATEWAY")
        .jsonPath("$.requestId").isEqualTo("connection-failure").jsonPath("$.stackTrace").doesNotExist();
  }
}
