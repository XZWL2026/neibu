package com.xzwl.cloud.iam;

import static org.junit.jupiter.api.Assertions.*;
import static com.xzwl.cloud.iam.ReleaseAuthMysqlTest.*;
import static com.xzwl.cloud.iam.UserSessionMysqlTest.*;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.Duration;

/** Real HTTP Gateway -> IAM/User with MySQL sessions; only external SMS delivery is recorded. */
@EnabledIfSystemProperty(named = "gateway.test.jar", matches = ".+")
@EnabledIfEnvironmentVariable(named = "IAM_TEST_MYSQL_URL", matches = ".+")
class GatewaySessionMysqlTest {
  @Test void phoneLoginRotationCsrfUserLookupAndLogoutThroughGateway() throws Exception {
    try (Database iamDb = new Database(); Database userDb = new Database()) {
      int userPort = freePort();
      Map<String,Object> settings = Map.of("iam.user-service-url", "http://127.0.0.1:" + userPort,
          "spring.cloud.gateway.server.webflux.enabled", false,
          "spring.cloud.nacos.discovery.enabled", false, "spring.cloud.nacos.config.enabled", false);
      try (var iam = start(iamDb, "prod", settings, SmsConfiguration.class);
           var user = startUser(userDb, userPort, iam.getWebServer().getPort());
           var gateway = new GatewayProcess(iam.getWebServer().getPort(), userPort)) {
        Browser browser = new Browser(iam);
        browser.base = "http://127.0.0.1:" + gateway.port;
        assertFalse(browser.session().path("authenticated").asBoolean());
        String oldCookie = browser.cookie();
        String oldCsrf = browser.csrf();
        // Existing-session refresh may omit Set-Cookie; initial response is checked using a new browser.
        var fresh = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(browser.base + "/api/v1/auth/session")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(fresh.headers().firstValue("set-cookie").orElseThrow().contains("HttpOnly"));
        assertTrue(fresh.headers().firstValue("set-cookie").orElseThrow().contains("SameSite=Lax"));
        assertEquals(403, browser.post("/api/v1/auth/otp/send", Map.of("phone", "13800000001"), null).statusCode());
        assertEquals(200, browser.post("/api/v1/auth/otp/send", Map.of("phone", "13800000001"), oldCsrf).statusCode());
        String code = iam.getBean(RecordingSms.class).codes.get("+8613800000001");
        assertEquals(200, browser.post("/api/v1/auth/otp/login", Map.of("phone", "13800000001", "code", code), oldCsrf).statusCode());
        assertNotEquals(oldCookie, browser.cookie());
        String csrf = browser.csrf(); assertNotEquals(oldCsrf, csrf);
        var self = get(browser, "/api/v1/users/me");
        assertEquals(200, self.statusCode(), self.body());
        assertEquals(browser.session().path("user").path("id").asText(), JSON.readTree(self.body()).path("id").asText());
        assertEquals(404, get(browser, "/internal/v1/iam/session").statusCode());
        assertEquals(404, browser.post("/api/v1/auth/admin/local-login", Map.of(), csrf).statusCode());
        assertEquals(403, browser.post("/api/v1/auth/logout", Map.of(), oldCsrf).statusCode());
        assertEquals(200, browser.post("/api/v1/auth/logout", Map.of(), csrf).statusCode());
        assertFalse(browser.session().path("authenticated").asBoolean());
        assertEquals(401, get(browser, "/api/v1/users/me").statusCode());
      }
    }
  }
  static final class GatewayProcess implements AutoCloseable {
    final int port;
    final Process process;
    GatewayProcess(int iamPort, int userPort) throws Exception {
      port = freePort();
      Path log = Files.createTempFile("xzwl-gateway-login-", ".log");
      Path jar = Path.of(System.getProperty("gateway.test.jar")).toAbsolutePath();
      assertTrue(Files.isRegularFile(jar), "Build the Gateway executable JAR first");
      process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString(),
          "--server.port=" + port, "--server.address=127.0.0.1", "--logging.level.root=ERROR",
          "--spring.cloud.nacos.discovery.enabled=false", "--spring.cloud.nacos.config.enabled=false",
          "--gateway.upstreams.iam=http://127.0.0.1:" + iamPort,
          "--gateway.upstreams.user=http://127.0.0.1:" + userPort)
          .redirectErrorStream(true).redirectOutput(log.toFile()).start();
      try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
        for (int attempt = 0; attempt < 150 && process.isAlive(); attempt++) {
          try {
            var ready = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (ready.statusCode() == 200) return;
          } catch (java.io.IOException notReady) { /* Wait only for this process's readiness. */ }
          Thread.sleep(100);
        }
      } catch (Exception failure) { close(); throw failure; }
      close(); throw new AssertionError("Gateway did not become ready: " + Files.readString(log));
    }
    @Override public void close() throws Exception {
      process.destroy();
      if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
        process.destroyForcibly(); process.waitFor();
      }
    }
  }
  static HttpResponse<String> get(Browser browser, String path) throws Exception {
    return browser.client.send(HttpRequest.newBuilder(URI.create(browser.base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }
}
