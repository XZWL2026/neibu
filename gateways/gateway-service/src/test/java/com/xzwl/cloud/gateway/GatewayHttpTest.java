package com.xzwl.cloud.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.main.web-application-type=reactive", "spring.cloud.nacos.discovery.enabled=false",
    "spring.cloud.nacos.config.enabled=false", "gateway.cors.allowed-origins=https://app.example.test"
})
class GatewayHttpTest {
  static final AtomicInteger HITS = new AtomicInteger();
  static final HttpServer UPSTREAM = upstream();
  @LocalServerPort int port;
  WebTestClient client;
  static HttpServer upstream() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        HITS.incrementAndGet();
        byte[] body = exchange.getRequestBody().readAllBytes();
        var input = exchange.getRequestHeaders();
        String probe = input.getFirst("X-Probe-Status");
        int status = probe == null ? 200 : Integer.parseInt(probe);
        var output = exchange.getResponseHeaders();
        output.add("Content-Type", "application/json");
        output.add("Set-Cookie", "XZWL_SESSION=rotated; Path=/; Secure; HttpOnly; SameSite=Lax");
        output.add("Set-Cookie", "second=cookie; Path=/; HttpOnly");
        output.add("Cache-Control", "no-store");
        byte[] reply = ReleaseContractTest.JSON.writeValueAsBytes(Map.of("path", exchange.getRequestURI().toString(),
            "method", exchange.getRequestMethod(), "body", new String(body, StandardCharsets.UTF_8),
            "cookie", Objects.toString(input.getFirst("Cookie"), ""), "csrf", Objects.toString(input.getFirst("X-CSRF-TOKEN"), ""),
            "requestId", Objects.toString(input.getFirst("X-Request-Id"), ""),
        "query", Objects.toString(exchange.getRequestURI().getRawQuery(), "")));
        exchange.sendResponseHeaders(status, reply.length);
        exchange.getResponseBody().write(reply); exchange.close();
      });
      server.start(); return server;
    } catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }
  @DynamicPropertySource static void upstreams(DynamicPropertyRegistry registry) {
    for (String service : ReleaseContractTest.SERVICES)
      registry.add("gateway.upstreams." + service, () -> "http://127.0.0.1:" + UPSTREAM.getAddress().getPort());
  }
  @BeforeEach void client() { client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build(); }
  @AfterAll static void stop() { UPSTREAM.stop(0); }

  @Test void forwardsEveryPublicControllerMethodAndExactPath() throws Exception {
    for (var e : ReleaseContractTest.endpoints()) {
      if (!ReleaseContractTest.isPublic(e)) continue;
      String path = e.path().replaceAll("\\{[^}]+}", "sample");
      client.method(org.springframework.http.HttpMethod.valueOf(e.verb())).uri(path).exchange()
          .expectStatus().isOk().expectBody().jsonPath("$.path").isEqualTo(path).jsonPath("$.method").isEqualTo(e.verb());
    }
  }
  @Test void blocksInternalLocalOnlyUnknownAndWrongMethodsBeforeAnyUpstream() throws Exception {
    int before = HITS.get();
    for (var e : ReleaseContractTest.endpoints()) if (!ReleaseContractTest.isPublic(e)) {
      client.method(org.springframework.http.HttpMethod.valueOf(e.verb()))
          .uri(e.path().replaceAll("\\{[^}]+}", "sample")).header("X-Service-Token", "untrusted-service-token")
          .header("X-IAM-INTERNAL-TOKEN", "untrusted-iam-token").exchange().expectStatus().isNotFound();
    }
    client.delete().uri("/api/v1/users/me").exchange().expectStatus().isNotFound();
    client.get().uri("/api/v1/unknown").exchange().expectStatus().isNotFound();
    client.options().uri("/internal/v1/iam/session").header("Origin", "https://app.example.test")
        .header("Access-Control-Request-Method", "GET").exchange().expectStatus().isNotFound();
    assertThat(HITS.get()).isEqualTo(before);
  }
  @Test void preservesCookieCsrfBodyAndEveryUpstreamErrorStatus() {
    for (int status : List.of(200, 400, 401, 403, 409, 429, 503)) {
      client.post().uri("/api/v1/auth/otp/login").header("Cookie", "XZWL_SESSION=original")
          .header("X-CSRF-TOKEN", "session-token").header("X-Probe-Status", Integer.toString(status))
          .header("X-Request-Id", "browser-123").header("Origin", "https://app.example.test")
          .bodyValue("{\"phone\":\"+8613800000000\",\"code\":\"123456\"}").exchange()
          .expectStatus().isEqualTo(status).expectHeader().valueEquals("Cache-Control", "no-store")
          .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://app.example.test")
          .expectHeader().valueEquals("Set-Cookie", "XZWL_SESSION=rotated; Path=/; Secure; HttpOnly; SameSite=Lax", "second=cookie; Path=/; HttpOnly")
          .expectBody().jsonPath("$.cookie").isEqualTo("XZWL_SESSION=original")
          .jsonPath("$.csrf").isEqualTo("session-token").jsonPath("$.requestId").isEqualTo("browser-123")
          .jsonPath("$.body").isEqualTo("{\"phone\":\"+8613800000000\",\"code\":\"123456\"}");
    }
  }
  @Test void handlesCredentialedCorsPreflightAndRejectsOtherOrigins() {
    int before = HITS.get();
    client.options().uri("/api/v1/media/tasks").header("Origin", "https://app.example.test")
        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "Content-Type,X-CSRF-TOKEN,Idempotency-Key")
        .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Credentials", "true")
        .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://app.example.test");
    client.post().uri("/api/v1/auth/otp/send").header("Origin", "https://untrusted.example.test").exchange().expectStatus().isForbidden()
        .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN").jsonPath("$.requestId").isNotEmpty();
    assertThat(HITS.get()).isEqualTo(before);
  }
  @Test void canvasDeleteAllowsCredentialedPreflightAndForwardsExpectedVersion() {
    client.options().uri("/api/v1/canvases/sample").header("Origin", "https://app.example.test")
        .header("Access-Control-Request-Method", "DELETE").header("Access-Control-Request-Headers", "X-CSRF-TOKEN")
        .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin", "https://app.example.test");
    client.delete().uri("/api/v1/canvases/sample?expectedVersion=3").header("Cookie", "XZWL_SESSION=original")
        .header("X-CSRF-TOKEN", "session-token").exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.method").isEqualTo("DELETE").jsonPath("$.cookie").isEqualTo("XZWL_SESSION=original")
        .jsonPath("$.csrf").isEqualTo("session-token").jsonPath("$.query").isEqualTo("expectedVersion=3");
  }
  @Test void preservesCallbackRawBytesAndMultipartBody() {
    String raw = "signature-sensitive + % body\r\n\"text\"";
    client.post().uri("/callbacks/payment").bodyValue(raw).exchange().expectStatus().isOk().expectBody().jsonPath("$.body").isEqualTo(raw);
    String multipart = "--test-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"image.png\"\r\nContent-Type: image/png\r\n\r\nimage-bytes\r\n--test-boundary--\r\n";
    client.post().uri("/api/v1/files/reference-images").header("Content-Type", "multipart/form-data; boundary=test-boundary")
        .bodyValue(multipart).exchange().expectStatus().isOk().expectBody().jsonPath("$.body").isEqualTo(multipart);
  }
  @Test void rejectsAmbiguousPathsAndReturnsCorrelatedJsonForMissingRoutes() {
    int before = HITS.get();
    for (String path : List.of("/api/v1/users/../internal/x", "/api/v1/users/%2e%2e/internal/x", "/api/v1/users/me;anything", "/api//v1/users/me", "/api/v1/users/%2fme"))
      client.get().uri(URI.create("http://127.0.0.1:" + port + path)).exchange().expectStatus().isBadRequest();
    client.get().uri("/missing").header("X-Request-Id", "trace-404").exchange().expectStatus().isNotFound()
        .expectHeader().valueEquals("X-Request-Id", "trace-404").expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND").jsonPath("$.requestId").isEqualTo("trace-404");
    assertThat(HITS.get()).isEqualTo(before);
  }
}
