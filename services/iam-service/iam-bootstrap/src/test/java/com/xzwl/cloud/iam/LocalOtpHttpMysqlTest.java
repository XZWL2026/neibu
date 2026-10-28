package com.xzwl.cloud.iam;

import static org.junit.jupiter.api.Assertions.*;
import static com.xzwl.cloud.iam.ReleaseAuthMysqlTest.*;
import static com.xzwl.cloud.iam.UserSessionMysqlTest.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real local provider, IAM HTTP/session persistence and User service; isolated disposable schemas. */
@EnabledIfEnvironmentVariable(named="IAM_TEST_MYSQL_URL", matches=".+")
class LocalOtpHttpMysqlTest {
  @Test void fixedCodeUsesNormalIdentitySessionCsrfAndPermissionChecks() throws Exception {
    try (Database iamDb = new Database(); Database userDb = new Database()) {
      int userPort = freePort();
      try (var iam = start(iamDb, "local", Map.of("IAM_LOCAL_OTP_ENABLED", "true", "IAM_LOCAL_OTP_CODE", "123456",
              "iam.user-service-url", "http://127.0.0.1:" + userPort));
           var user = startUser(userDb, userPort, iam.getWebServer().getPort())) {
        var browser = new Browser(iam);
        var phone = Map.<String,Object>of("phone", "13800000001");
        var login = Map.<String,Object>of("phone", "13800000001", "code", "123456");
        int adminCount = iamDb.jdbc.queryForObject("SELECT COUNT(*) FROM iam_admin", Integer.class);
        assertEquals(401, browser.post("/api/v1/auth/otp/login", login, browser.csrf()).statusCode());
        assertEquals(403, browser.post("/api/v1/auth/otp/send", phone, null).statusCode());
        var sent = browser.post("/api/v1/auth/otp/send", phone, browser.csrf());
        assertEquals(200, sent.statusCode(), sent.body());
        assertFalse(sent.body().contains("123456"));
        assertEquals(429, browser.post("/api/v1/auth/otp/send", phone, browser.csrf()).statusCode());
        assertEquals(401, browser.post("/api/v1/auth/otp/login", Map.of("phone", "13800000001", "code", "654321"), browser.csrf()).statusCode());
        assertFalse(browser.session().path("authenticated").asBoolean());
        assertEquals(0, userDb.jdbc.queryForObject("SELECT COUNT(*) FROM user_identity", Integer.class));
        String oldCsrf = browser.csrf();
        String oldCookie = browser.cookie();
        assertEquals(403, browser.post("/api/v1/auth/otp/login", login, null).statusCode());
        var loggedIn = browser.post("/api/v1/auth/otp/login", login, oldCsrf);
        assertEquals(200, loggedIn.statusCode(), loggedIn.body());
        assertNotEquals(oldCookie, browser.cookie());
        assertTrue(loggedIn.headers().allValues("set-cookie").stream().anyMatch(c ->
            c.contains("XZWL_SESSION=") && c.contains("HttpOnly") && c.contains("SameSite=Lax")));
        var session = browser.session();
        assertTrue(session.path("authenticated").asBoolean());
        assertEquals("user", session.path("roles").get(0).asText());
        assertFalse(session.path("primary").asBoolean());
        assertNotEquals(oldCsrf, session.path("csrfToken").asText());
        String userId = session.path("user").path("id").asText();
        assertEquals(200, userRequest(browser, user, "GET", "/api/v1/users/me", Map.of(), null).statusCode());
        assertEquals(403, browser.send("GET", "/api/v1/admin/admins", Map.of(), null).statusCode());
        assertEquals(403, browser.send("GET", "/api/v1/admin/iam/sms-provider-status", Map.of(), null).statusCode());
        assertEquals(adminCount, iamDb.jdbc.queryForObject("SELECT COUNT(*) FROM iam_admin", Integer.class));
        assertEquals(401, browser.post("/api/v1/auth/otp/login", login, browser.csrf()).statusCode());
        assertEquals(403, browser.post("/api/v1/auth/logout", Map.of(), oldCsrf).statusCode());
        assertEquals(200, browser.post("/api/v1/auth/logout", Map.of(), browser.csrf()).statusCode());

        // Account disabling goes through the normal administrator endpoint.
        var admin = new Browser(iam);
        assertEquals(200, admin.post("/api/v1/auth/admin/login", credentials(), admin.csrf()).statusCode());
        assertEquals(200, userRequest(admin, user, "PATCH", "/api/v1/admin/users/" + userId + "/status",
            Map.of("enabled", false), admin.csrf()).statusCode());
        // Advance only the test challenge timestamp instead of waiting a real minute.
        iamDb.jdbc.update("UPDATE iam_phone_otp SET last_sent_at=last_sent_at-60000");
        assertEquals(200, browser.post("/api/v1/auth/otp/send", phone, browser.csrf()).statusCode());
        var disabled = browser.post("/api/v1/auth/otp/login", login, browser.csrf());
        assertEquals(403, disabled.statusCode(), disabled.body());
        assertTrue(disabled.body().contains("USER_DISABLED"));
        assertFalse(browser.session().path("authenticated").asBoolean());

        var lockedPhone = Map.<String,Object>of("phone", "13800000002");
        assertEquals(200, browser.post("/api/v1/auth/otp/send", lockedPhone, browser.csrf()).statusCode());
        for (int i = 0; i < 5; i++) assertEquals(i == 4 ? 423 : 401,
            browser.post("/api/v1/auth/otp/login", Map.of("phone", "13800000002", "code", "000000"), browser.csrf()).statusCode());
        assertEquals(423, browser.post("/api/v1/auth/otp/login", Map.of("phone", "13800000002", "code", "123456"), browser.csrf()).statusCode());
      }
    }
  }

  @Test void disabledModeDoesNotIssueOrAcceptFixedCode() throws Exception {
    try (Database db = new Database()) {
      try (var enabled = start(db, "local", Map.of("IAM_LOCAL_OTP_ENABLED", "true", "IAM_LOCAL_OTP_CODE", "123456"))) {
        var browser = new Browser(enabled);
        assertEquals(200, browser.post("/api/v1/auth/otp/send", Map.of("phone", "13800000001"), browser.csrf()).statusCode());
      }
      try (var iam = start(db, "local", Map.of("IAM_LOCAL_OTP_ENABLED", "false", "IAM_LOCAL_OTP_CODE", "123456",
          "iam.user-service-url", "http://127.0.0.1:" + freePort()))) {
      var browser = new Browser(iam);
      assertEquals(503, browser.post("/api/v1/auth/otp/send", Map.of("phone", "13800000003"), browser.csrf()).statusCode());
      assertEquals(401, browser.post("/api/v1/auth/otp/login", Map.of("phone", "13800000001", "code", "123456"), browser.csrf()).statusCode());
      assertFalse(browser.session().path("authenticated").asBoolean());
      }
    }
  }
}
