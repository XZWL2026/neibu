package com.xzwl.cloud.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "IAM_TEST_MYSQL_URL", matches = ".+")
class ReleaseAuthMysqlTest {
  static final ObjectMapper JSON = new ObjectMapper();

  @Test void missingSmsAdapterFailsClosed() throws Exception {
    try (Database db = new Database(); var app = start(db, "prod")) {
      var browser = new Browser(app);
      var response = browser.post("/api/v1/auth/otp/send", Map.of("phone", "13800000001"), browser.csrf());
      assertEquals(503, response.statusCode(), response.body());
      assertTrue(response.body().contains("SMS_PROVIDER_UNAVAILABLE"));
      assertFalse(browser.session().path("authenticated").asBoolean());
    }
  }

  @Test void localTokenIsIsolatedFromProductionPasswordLogin() throws Exception {
    assertFalse(new com.xzwl.cloud.iam.application.account.AdminLoginHandler(null).authenticate(""));
    assertFalse(new com.xzwl.cloud.iam.application.account.AdminLoginHandler("").authenticate(""));
    try (Database db = new Database()) {
      for (String profile : List.of("default", "prod", "local,production", "local")) {
        try (var app = start(db, profile)) {
          boolean local = profile.equals("local");
          assertEquals(local, !app.getBeansOfType(com.xzwl.cloud.iam.interfaces.http.LocalAdminSessionController.class).isEmpty());
          var browser = new Browser(app);
          var tokenResult = browser.post("/api/v1/auth/admin/local-login", Map.of("token", System.getenv("IAM_ADMIN_LOGIN_TOKEN")), browser.csrf());
          assertEquals(local ? 200 : 404, tokenResult.statusCode(), tokenResult.body());
          assertEquals(200, browser.post("/api/v1/auth/admin/login", credentials(), browser.csrf()).statusCode());
        }
      }
    }
  }

  @Test void adminMultiDeviceCsrfPasswordStatusAndRoleBoundaries() throws Exception {
    try (Database db = new Database(); var app = start(db, "prod")) {
      var first = new Browser(app);
      String initialCsrf = first.csrf();
      String initialCookie = first.cookie();
      assertEquals(403, first.post("/api/v1/auth/admin/login", credentials(), null).statusCode());
      assertEquals(401, first.post("/api/v1/auth/admin/login", Map.of("username", credentials().get("username"), "password", "invalid-password"), initialCsrf).statusCode());
      assertEquals(200, first.post("/api/v1/auth/admin/login", credentials(), initialCsrf).statusCode());
      assertNotEquals(initialCookie, first.cookie());
      assertNotEquals(initialCsrf, first.csrf());
      assertEquals(403, first.post("/internal/v1/iam/session/verify", Map.of(), initialCsrf).statusCode());
      var second = new Browser(app);
      assertEquals(200, second.post("/api/v1/auth/admin/login", credentials(), second.csrf()).statusCode());
      assertNotEquals(first.cookie(), second.cookie());
      assertEquals(86400, db.jdbc.queryForObject("SELECT MAX(MAX_INACTIVE_INTERVAL) FROM SPRING_SESSION", Integer.class));
      String password = UUID.randomUUID().toString();
      var created = first.post("/api/v1/admin/admins", Map.of("username", "normal", "password", password, "role", "NORMAL_ADMIN"), first.csrf());
      assertEquals(201, created.statusCode(), created.body());
      String id = JSON.readTree(created.body()).path("id").asText();
      assertFalse(created.body().contains(password));
      assertEquals(409, first.post("/api/v1/admin/admins", Map.of("username", "normal", "password", password, "role", "NORMAL_ADMIN"), first.csrf()).statusCode());
      assertEquals(400, first.post("/api/v1/admin/admins", Map.of("username", "short-password", "password", "short", "role", "NORMAL_ADMIN"), first.csrf()).statusCode());
      var normal = new Browser(app);
      assertEquals(200, normal.post("/api/v1/auth/admin/login", Map.of("username", "normal", "password", password), normal.csrf()).statusCode());
      assertEquals(403, normal.post("/api/v1/admin/admins", Map.of("username", "illegal", "password", password, "role", "ADMIN"), normal.csrf()).statusCode());
      assertEquals(403, normal.send("PATCH", "/api/v1/admin/admins/" + id + "/role", Map.of("role", "ADMIN"), normal.csrf()).statusCode());
      assertEquals(403, normal.send("PATCH", "/api/v1/admin/admins/" + id + "/status", Map.of("enabled", false), normal.csrf()).statusCode());
      String primaryId = first.session().path("user").path("id").asText();
      assertEquals(403, normal.send("PATCH", "/api/v1/admin/admins/" + primaryId + "/status", Map.of("enabled", false), normal.csrf()).statusCode());
      assertEquals(200, first.send("PATCH", "/api/v1/admin/admins/" + id + "/status", Map.of("enabled", false), first.csrf()).statusCode());
      assertFalse(normal.session().path("authenticated").asBoolean());
      assertEquals(401, normal.post("/api/v1/auth/admin/login", Map.of("username", "normal", "password", password), normal.csrf()).statusCode());
      assertEquals(200, first.send("PATCH", "/api/v1/admin/admins/" + id + "/status", Map.of("enabled", true), first.csrf()).statusCode());
      assertFalse(normal.session().path("authenticated").asBoolean());
      assertEquals(200, normal.post("/api/v1/auth/admin/login", Map.of("username", "normal", "password", password), normal.csrf()).statusCode());
      var otherCreated = first.post("/api/v1/admin/admins", Map.of("username", "other-normal", "password", password, "role", "NORMAL_ADMIN"), first.csrf());
      assertEquals(201, otherCreated.statusCode());
      String otherId = JSON.readTree(otherCreated.body()).path("id").asText();
      var other = new Browser(app);
      assertEquals(200, other.post("/api/v1/auth/admin/login", Map.of("username", "other-normal", "password", password), other.csrf()).statusCode());
      String resetPassword = UUID.randomUUID().toString();
      assertEquals(200, normal.post("/api/v1/admin/admins/" + otherId + "/password", Map.of("password", resetPassword), normal.csrf()).statusCode());
      assertFalse(other.session().path("authenticated").asBoolean());
      assertEquals(200, other.post("/api/v1/auth/admin/login", Map.of("username", "other-normal", "password", resetPassword), other.csrf()).statusCode());
      assertEquals(200, first.send("PATCH", "/api/v1/admin/admins/" + otherId + "/role", Map.of("role", "ADMIN"), first.csrf()).statusCode());
      assertFalse(other.session().path("authenticated").asBoolean());
      assertEquals(403, normal.send("PATCH", "/api/v1/admin/admins/" + otherId + "/status", Map.of("enabled", false), normal.csrf()).statusCode());
      assertEquals(200, other.post("/api/v1/auth/admin/login", Map.of("username", "other-normal", "password", resetPassword), other.csrf()).statusCode());
      assertEquals(403, other.post("/api/v1/admin/admins", Map.of("username", "not-primary", "password", password, "role", "NORMAL_ADMIN"), other.csrf()).statusCode());
      assertEquals(200, first.post("/api/v1/auth/logout", Map.of(), first.csrf()).statusCode());
      assertFalse(first.session().path("authenticated").asBoolean());
      assertTrue(second.session().path("authenticated").asBoolean());
      assertEquals(400, second.post("/api/v1/auth/admin/password", Map.of("currentPassword", credentials().get("password"), "newPassword", "short"), second.csrf()).statusCode());
      assertEquals(200, first.post("/api/v1/auth/admin/login", credentials(), first.csrf()).statusCode());
      assertEquals(200, second.post("/api/v1/auth/admin/password", Map.of("currentPassword", credentials().get("password"), "newPassword", UUID.randomUUID().toString()), second.csrf()).statusCode());
      assertFalse(first.session().path("authenticated").asBoolean());
      assertFalse(second.session().path("authenticated").asBoolean());
      assertEquals(401, first.post("/api/v1/auth/admin/login", credentials(), first.csrf()).statusCode());
    }
  }

  @Test void bootstrapSerializesAndNeverReadsEnvironmentForExistingAccounts() throws Exception {
    try (Database db = new Database(); var app = start(db, "prod")) {
      var admins = app.getBean(com.xzwl.cloud.iam.application.account.AdminAccounts.class);
      admins.bootstrap(key -> { fail("Existing administrators must prevent reading bootstrap variables"); return null; }, true);
      db.jdbc.update("DELETE FROM iam_admin");
      IllegalStateException missing = assertThrows(IllegalStateException.class, () -> admins.bootstrap(key -> null, true));
      assertTrue(missing.getMessage().contains("Administrator table is empty"));
      assertThrows(IllegalStateException.class, () -> admins.bootstrap(key -> "short", true));
      try (var workers = java.util.concurrent.Executors.newFixedThreadPool(6)) {
        var ready = new java.util.concurrent.CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < 6; i++) futures.add(workers.submit(() -> { ready.await(); admins.bootstrap(System::getenv, true); return null; }));
        ready.countDown();
        for (var result : futures) result.get();
      }
      assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM iam_admin", Integer.class));
      assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM iam_admin WHERE is_primary=TRUE AND role='ADMIN'", Integer.class));
      assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.jdbc.update("INSERT INTO iam_admin(admin_id,username,password_hash,role,is_primary) SELECT 'another','another',password_hash,'ADMIN',TRUE FROM iam_admin"));
    }
  }

  @Test void productionPasswordLoginBootstrapsOnlyOnceAndPersistsAcrossRestart() throws Exception {
    try (Database db = new Database()) {
      Browser browser;
      try (var app = start(db, "prod")) {
        browser = new Browser(app);
        String csrf = browser.session().path("csrfToken").asText();
        var response = browser.post("/api/v1/auth/admin/login", credentials(), csrf);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(browser.session().path("authenticated").asBoolean());
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM iam_admin", Integer.class));
        String hash = db.jdbc.queryForObject("SELECT password_hash FROM iam_admin", String.class);
        assertNotEquals(System.getenv("IAM_BOOTSTRAP_ADMIN_PASSWORD"), hash);
        assertTrue(hash.startsWith("$2"));
      }
      try (var restarted = start(db, "prod")) {
        browser.base = "http://127.0.0.1:" + restarted.getWebServer().getPort();
        assertTrue(browser.session().path("authenticated").asBoolean());
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM iam_admin", Integer.class));
      }
    }
  }

  static Map<String, Object> credentials() {
    return Map.of("username", System.getenv("IAM_BOOTSTRAP_ADMIN_USERNAME"),
        "password", System.getenv("IAM_BOOTSTRAP_ADMIN_PASSWORD"));
  }

  static ServletWebServerApplicationContext start(Database db, String profiles, Class<?>... extraSources) {
    return start(db, profiles, Map.of(), extraSources);
  }
  static ServletWebServerApplicationContext start(Database db, String profiles, Map<String,Object> additional, Class<?>... extraSources) {
    var env = new StandardEnvironment();
    env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var properties = new HashMap<String,Object>(Map.of(
        "spring.datasource.url", db.url, "spring.datasource.username", "root", "spring.datasource.password", "",
        "spring.main.banner-mode", "off", "logging.level.root", "ERROR",
        "server.servlet.session.cookie.secure", false));
    properties.putAll(additional);
    env.getPropertySources().addFirst(new MapPropertySource("releaseTest", properties));
    return (ServletWebServerApplicationContext) new SpringApplicationBuilder(TemplateApplication.class)
        .sources(extraSources).environment(env).web(WebApplicationType.SERVLET)
        // Load local fixture configuration first; exercise real auth profiles without production Nacos imports.
        .initializers(context -> context.getEnvironment().setActiveProfiles(profiles.split(",")))
        .run("--spring.config.location=classpath:/application.yml", "--spring.profiles.active=auth-mysql-test",
            "--server.port=0", "--server.address=127.0.0.1");
  }

  static final class Database implements AutoCloseable {
    final JdbcTemplate root;
    final String schema = "iam_release_test_" + UUID.randomUUID().toString().replace("-", "");
    final String url;
    final JdbcTemplate jdbc;
    Database() {
      String rootUrl = System.getenv("IAM_TEST_MYSQL_URL");
      if (!rootUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/")) throw new IllegalArgumentException("Local test MySQL required");
      root = new JdbcTemplate(new DriverManagerDataSource(rootUrl, "root", ""));
      root.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
      url = rootUrl + schema + "?connectionTimeZone=UTC";
      jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", ""));
    }
    public void close() { root.execute("DROP DATABASE " + schema); }
  }

  static final class Browser {
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
    String base;
    Browser(ServletWebServerApplicationContext app) { base = "http://127.0.0.1:" + app.getWebServer().getPort(); }
    JsonNode session() throws Exception {
      var r = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/session")).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, r.statusCode(), r.body());
      return JSON.readTree(r.body());
    }
    HttpResponse<String> post(String path, Map<String, Object> body, String csrf) throws Exception {
      return send("POST", path, body, csrf);
    }
    HttpResponse<String> send(String method, String path, Map<String, Object> body, String csrf) throws Exception {
      var req = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
      if (csrf != null) req.header("X-CSRF-TOKEN", csrf);
      return client.send(req.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    String csrf() throws Exception { return session().path("csrfToken").asText(); }
    String cookie() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XZWL_SESSION")).findFirst().orElseThrow().getValue(); }
  }
}
