package com.xzwl.cloud.iam;

import static org.junit.jupiter.api.Assertions.*;
import static com.xzwl.cloud.iam.ReleaseAuthMysqlTest.*;
import static com.xzwl.cloud.iam.UserSessionMysqlTest.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="IAM_TEST_MYSQL_URL", matches=".+")
class PhoneLoginHttpMysqlTest {
  static final String LOGIN = "/api/v1/auth/phone/login";

  @Test void enabledModeCreatesAndReusesUserWithNormalSessionAndPermissionChecks() throws Exception {
    try (Database iamDb = new Database(); Database userDb = new Database()) {
      int userPort = freePort();
      try (var iam = start(iamDb, "prod", Map.of("IAM_PHONE_ONLY_LOGIN_ENABLED", "true",
              "iam.user-service-url", "http://127.0.0.1:" + userPort));
           var user = startUser(userDb, userPort, iam.getWebServer().getPort())) {
        var browser = new Browser(iam);
        var phone = Map.<String,Object>of("phone", "13800000001");
        String csrf = browser.csrf(), cookie = browser.cookie();
        assertEquals(403, browser.post(LOGIN, phone, null).statusCode());
        assertEquals(400, browser.post(LOGIN, Map.of("phone", "bad"), csrf).statusCode());
        assertEquals(0, userDb.jdbc.queryForObject("SELECT COUNT(*) FROM user_identity", Integer.class));
        var response = browser.post(LOGIN, phone, csrf);
        assertEquals(200, response.statusCode(), response.body());
        var session = browser.session();
        assertTrue(session.path("authenticated").asBoolean());
        assertEquals("user", session.path("roles").get(0).asText());
        assertFalse(session.path("primary").asBoolean());
        assertNotEquals(cookie, browser.cookie());
        assertNotEquals(csrf, browser.csrf());
        String id = session.path("user").path("id").asText();
        assertEquals(403, browser.send("GET", "/api/v1/admin/admins", Map.of(), null).statusCode());
        assertEquals(0, iamDb.jdbc.queryForObject("SELECT COUNT(*) FROM iam_phone_otp", Integer.class));
        assertEquals(200, browser.post("/api/v1/auth/logout", Map.of(), browser.csrf()).statusCode());
        assertEquals(200, browser.post(LOGIN, Map.of("phone", "+8613800000001"), browser.csrf()).statusCode());
        assertEquals(id, browser.session().path("user").path("id").asText());
        assertEquals(1, userDb.jdbc.queryForObject("SELECT COUNT(*) FROM user_identity", Integer.class));
        assertEquals(200, browser.post("/api/v1/auth/logout", Map.of(), browser.csrf()).statusCode());
        var admin = new Browser(iam);
        assertEquals(200, admin.post("/api/v1/auth/admin/login", credentials(), admin.csrf()).statusCode());
        assertEquals(200, userRequest(admin, user, "PATCH", "/api/v1/admin/users/" + id + "/status", Map.of("enabled", false), admin.csrf()).statusCode());
        response = browser.post(LOGIN, phone, browser.csrf());
        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains("USER_DISABLED"));
        assertFalse(browser.session().path("authenticated").asBoolean());
      }
    }
  }

  @Test void defaultAndExplicitlyDisabledModesRejectWithoutResolvingUsers() throws Exception {
    try (Database db = new Database()) {
      for (var settings : java.util.List.of(Map.<String,Object>of(), Map.<String,Object>of("IAM_PHONE_ONLY_LOGIN_ENABLED", "false"))) {
        try (var iam = start(db, "prod", settings)) {
          var browser = new Browser(iam);
          var result = browser.post(LOGIN, Map.of("phone", "13800000001"), browser.csrf());
          assertEquals(403, result.statusCode(), result.body());
          assertTrue(result.body().contains("PHONE_ONLY_LOGIN_DISABLED"));
          assertFalse(browser.session().path("authenticated").asBoolean());
        }
      }
    }
  }
}
