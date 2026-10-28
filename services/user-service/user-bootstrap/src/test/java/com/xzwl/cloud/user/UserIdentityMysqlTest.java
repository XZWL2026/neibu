package com.xzwl.cloud.user;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.user.application.identity.UserIdentityStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="IAM_TEST_MYSQL_URL", matches=".+")
class UserIdentityMysqlTest {
  @Test void internalDirectoryIsAuthenticatedUniqueAndDurable() throws Exception {
    String rootUrl = System.getenv("IAM_TEST_MYSQL_URL");
    assertTrue(rootUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    var db = new JdbcTemplate(new DriverManagerDataSource(rootUrl, username(), password()));
    String schema = "user_release_test_" + UUID.randomUUID().toString().replace("-", "");
    db.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
    try {
      String id;
      try (var app = start(rootUrl + schema)) {
        String url = "http://127.0.0.1:" + app.getWebServer().getPort() + "/internal/v1/users/resolve";
        assertEquals(401, resolve(url, null).statusCode());
        var first = resolve(url, System.getenv("IAM_USER_INTERNAL_TOKEN"));
        assertEquals(200, first.statusCode(), first.body());
        id = new ObjectMapper().readTree(first.body()).path("id").asText();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
          var results = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
          for (int i=0;i<8;i++) results.add(workers.submit(() -> resolve(url, System.getenv("IAM_USER_INTERNAL_TOKEN"))));
          for (var result : results) assertEquals(id, new ObjectMapper().readTree(result.get().body()).path("id").asText());
        }
        var users = app.getBean(UserIdentityStore.class);
        assertEquals(0, users.find(id).authVersion());
        users.setEnabled(id, false);
        users.setEnabled(id, false);
        assertFalse(users.find(id).enabled());
        assertEquals(1, users.find(id).authVersion());
        users.setEnabled(id, true);
        users.setEnabled(id, true);
        assertTrue(users.find(id).enabled());
        assertEquals(2, users.find(id).authVersion());
      }
      try (var app = start(rootUrl + schema)) {
        var response = resolve("http://127.0.0.1:" + app.getWebServer().getPort() + "/internal/v1/users/resolve", System.getenv("IAM_USER_INTERNAL_TOKEN"));
        assertEquals(id, new ObjectMapper().readTree(response.body()).path("id").asText());
        assertEquals(2, app.getBean(UserIdentityStore.class).find(id).authVersion());
      }
    } finally { db.execute("DROP DATABASE " + schema); }
  }
  @Test void adminDirectoryEnforcesIdentityPermissionsIdempotencyAndDurability() throws Exception {
    String rootUrl = System.getenv("IAM_TEST_MYSQL_URL");
    assertTrue(rootUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    var db = new JdbcTemplate(new DriverManagerDataSource(rootUrl, username(), password()));
    String schema = "user_admin_test_" + UUID.randomUUID().toString().replace("-", "");
    db.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
    var iam = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    iam.createContext("/internal/v1/iam/session", exchange -> {
      String cookie = exchange.getRequestHeaders().getFirst("Cookie");
      String role = "admin".equals(cookie) || "admin2".equals(cookie) ? "admin" : "normal".equals(cookie) ? "normal_admin" : "user";
      boolean denied = exchange.getRequestMethod().equals("POST") && !"csrf".equals(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"));
      byte[] body = (denied ? "{}" : "{\"authenticated\":true,\"roles\":[\"" + role + "\"],\"user\":{\"id\":\"" + cookie + "\",\"username\":\"test\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(denied ? 403 : 200, body.length);
      exchange.getResponseBody().write(body); exchange.close();
    });
    iam.start();
    String iamUrl = "http://127.0.0.1:" + iam.getAddress().getPort();
    String create = "{\"requestId\":\"create-1\",\"phone\":\"+8613800000002\",\"nickname\":\"测试用户\",\"source\":\"REFERRAL\",\"remark\":\" 客户备注 \"}";
    String id;
    try {
      try (var app = start(rootUrl + schema, iamUrl)) {
        String base = "http://127.0.0.1:" + app.getWebServer().getPort();
        String collection = base + "/api/v1/admin/users";
        assertEquals(401, send(collection, "GET", null, null).statusCode());
        assertEquals(403, send(collection, "GET", "user", null).statusCode());
        assertEquals(403, send(collection, "POST", "user", create).statusCode());
        assertEquals(403, send(collection, "POST", "admin-no-csrf", create).statusCode());
        var created = send(collection, "POST", "admin", create);
        assertEquals(200, created.statusCode(), created.body());
        id = json(created).path("id").asText();
        assertEquals("+8613800000002", json(created).path("phone").asText());
        assertEquals(id, json(send(collection, "POST", "admin", create)).path("id").asText());
        assertFalse(json(created).has("remark"));
        assertFalse(json(created).has("source"));
        assertEquals(409, send(collection, "POST", "admin", create.replace("REFERRAL", "NATURAL")).statusCode());
        assertEquals(409, send(collection, "POST", "admin", create.replace(" 客户备注 ", "修改备注")).statusCode());
        assertEquals(400, send(collection, "POST", "admin", create.replace("REFERRAL", "UNKNOWN")).statusCode());
        assertEquals(400, send(collection, "POST", "admin", create.replace("\"REFERRAL\"", "0")).statusCode());
        assertEquals(400, send(collection, "POST", "admin", create.replace("REFERRAL", "")).statusCode());
        assertEquals(400, send(collection, "POST", "admin", create.replace(" 客户备注 ", "x".repeat(201))).statusCode());
        var metadata = app.getBean(JdbcTemplate.class).queryForMap("SELECT source,remark FROM admin_user_create_request WHERE request_id='create-1'");
        assertEquals("REFERRAL", metadata.get("source"));
        assertEquals(" 客户备注 ", metadata.get("remark"));
        var changed = send(collection, "POST", "admin", create.replace("测试用户", "changed"));
        assertEquals(409, changed.statusCode()); assertEquals("IDEMPOTENCY_CONFLICT", json(changed).path("code").asText());
        assertEquals(409, send(collection, "POST", "admin2", create).statusCode());
        assertEquals(409, send(collection, "POST", "admin", create.replace("0002", "0009")).statusCode());
        assertEquals(409, send(collection, "POST", "admin", create.replace("测试用户", " 测试用户 ")).statusCode());
        var duplicate = send(collection, "POST", "admin", create.replace("create-1", "create-duplicate"));
        assertEquals(409, duplicate.statusCode()); assertEquals("USER_PHONE_EXISTS", json(duplicate).path("code").asText());
        assertEquals(0, app.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM admin_user_create_request WHERE request_id='create-duplicate'", Integer.class));
        assertEquals(400, send(collection, "POST", "admin", create.replace("+8613800000002", "13800000002")).statusCode());
        assertEquals(400, send(collection, "POST", "admin", create.replace("测试用户", "  ")).statusCode());
        assertEquals(400, send(collection + "?limit=101", "GET", "admin", null).statusCode());
        assertEquals(400, send(collection + "?offset=-1", "GET", "admin", null).statusCode());
        assertEquals(200, send(collection + "/" + id, "GET", "normal", null).statusCode());
        assertEquals("+86138****0002", json(send(collection + "/" + id, "GET", "normal", null)).path("phone").asText());
        assertEquals("+8613800000002", json(send(collection + "/" + id, "GET", "admin", null)).path("phone").asText());
        assertEquals(404, send(collection + "/missing", "GET", "admin", null).statusCode());
        var pageResponse = send(collection + "?offset=0&limit=1&search=" + id, "GET", "normal", null);
        assertEquals(200, pageResponse.statusCode(), pageResponse.body());
        var page = json(pageResponse);
        assertEquals(1, page.path("total").asInt()); assertEquals(1, page.path("items").size());
        assertEquals("+86138****0002", page.path("items").get(0).path("phone").asText());
        assertEquals(1, json(send(collection + "?search=%2B8613800000002", "GET", "admin", null)).path("total").asInt());
        assertEquals(1, json(send(collection + "?search=" + java.net.URLEncoder.encode("测试", java.nio.charset.StandardCharsets.UTF_8), "GET", "admin", null)).path("total").asInt());
        assertEquals(0, json(send(collection + "?offset=1&limit=1", "GET", "admin", null)).path("items").size());
        String concurrent = create.replace("create-1", "concurrent").replace("0002", "0003");
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(8)) {
          var results = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
          for (int i = 0; i < 8; i++) results.add(workers.submit(() -> send(collection, "POST", "normal", concurrent)));
          String concurrentId = json(results.get(0).get()).path("id").asText();
          for (var result : results) { var response = result.get(); assertEquals(200, response.statusCode(), response.body()); assertEquals(concurrentId, json(response).path("id").asText()); }
          var logins = new ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
          for (int i = 0; i < 8; i++) logins.add(workers.submit(() -> resolve(base + "/internal/v1/users/resolve", System.getenv("IAM_USER_INTERNAL_TOKEN"), "+8613800000002")));
          for (var login : logins) assertEquals(id, json(login.get()).path("id").asText());
          var gate = new java.util.concurrent.CountDownLatch(1);
          var adminRace = workers.submit(() -> { gate.await(); return send(collection, "POST", "admin", create.replace("create-1", "login-race").replace("0002", "0004")); });
          var loginRace = workers.submit(() -> { gate.await(); return resolve(base + "/internal/v1/users/resolve", System.getenv("IAM_USER_INTERNAL_TOKEN"), "+8613800000004"); });
          gate.countDown();
          var raceCreate = adminRace.get(); var raceLogin = loginRace.get();
          assertEquals(200, raceLogin.statusCode(), raceLogin.body());
          assertTrue(raceCreate.statusCode() == 200 || raceCreate.statusCode() == 409, raceCreate.body());
          if (raceCreate.statusCode() == 200) assertEquals(json(raceLogin).path("id"), json(raceCreate).path("id"));
          else assertEquals("USER_PHONE_EXISTS", json(raceCreate).path("code").asText());
          assertEquals(1, json(send(collection + "?search=%2B8613800000004", "GET", "admin", null)).path("total").asInt());
        }
        assertEquals(200, send(collection + "/" + id + "/status", "PATCH", "normal", "{\"enabled\":false}").statusCode());
        assertEquals(200, send(collection + "/" + id + "/status", "PATCH", "normal", "{\"enabled\":false}").statusCode());
        assertEquals(1, app.getBean(UserIdentityStore.class).find(id).authVersion());
        String nicknameUrl = collection + "/" + id + "/nickname";
        assertEquals(403, send(nicknameUrl, "PATCH", "user", "{\"nickname\":\"forbidden\"}").statusCode());
        assertEquals(403, send(nicknameUrl, "PATCH", "admin-no-csrf", "{\"nickname\":\"forbidden\"}").statusCode());
        assertEquals(400, send(nicknameUrl, "PATCH", "admin", "{\"nickname\":\" \"}").statusCode());
        var renamed = send(nicknameUrl, "PATCH", "normal", "{\"nickname\":\" 新用户名 \"}");
        assertEquals(200, renamed.statusCode(), renamed.body());
        assertEquals("新用户名", json(renamed).path("nickname").asText());
        assertTrue(json(renamed).path("phone").asText().contains("****"));
        assertFalse(json(renamed).path("enabled").asBoolean());
        assertEquals(1, json(renamed).path("authVersion").asLong());
        assertEquals(200, send(nicknameUrl, "PATCH", "admin", "{\"nickname\":\"新用户名\"}").statusCode());
        assertEquals(1, json(send(collection + "?search=" + java.net.URLEncoder.encode("新用户名", java.nio.charset.StandardCharsets.UTF_8), "GET", "admin", null)).path("total").asInt());
        assertEquals(3, json(send(collection, "GET", "admin", null)).path("total").asInt());
      }
      try (var app = start(rootUrl + schema, iamUrl)) {
        String collection = "http://127.0.0.1:" + app.getWebServer().getPort() + "/api/v1/admin/users";
        var replay = send(collection, "POST", "admin", create);
        assertEquals(200, replay.statusCode()); assertEquals(id, json(replay).path("id").asText());
        assertFalse(json(replay).path("enabled").asBoolean()); assertEquals(1, json(replay).path("authVersion").asInt());
        assertEquals("新用户名", json(replay).path("nickname").asText());
        var metadata = app.getBean(JdbcTemplate.class).queryForMap("SELECT source,remark FROM admin_user_create_request WHERE request_id='create-1'");
        assertEquals("REFERRAL", metadata.get("source")); assertEquals(" 客户备注 ", metadata.get("remark"));
        assertEquals(3, json(send(collection, "GET", "admin", null)).path("total").asInt());
      }
    } finally { iam.stop(0); db.execute("DROP DATABASE " + schema); }
  }
  @Test void selfProfilePersistsOnlyOwnedValidatedFieldsAndRequiresCsrf() throws Exception {
    String rootUrl=System.getenv("IAM_TEST_MYSQL_URL");
    assertTrue(rootUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"));
    var db=new JdbcTemplate(new DriverManagerDataSource(rootUrl,username(),password()));
    String schema="profile_test_"+UUID.randomUUID().toString().replace("-","");
    db.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
    var dependencies=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    dependencies.createContext("/internal/v1/iam/session", exchange -> {
      String cookie=exchange.getRequestHeaders().getFirst("Cookie");
      boolean csrf=exchange.getRequestMethod().equals("POST") && !"csrf".equals(exchange.getRequestHeaders().getFirst("X-CSRF-TOKEN"));
      byte[] body=("{\"authenticated\":true,\"roles\":[\"user\"],\"user\":{\"id\":\""+cookie+"\",\"username\":\"test\"}}").getBytes();
      exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(csrf?403:200,body.length);
      exchange.getResponseBody().write(body);exchange.close();
    });
    var avatarChecks=new java.util.concurrent.atomic.AtomicInteger();
    dependencies.createContext("/api/v1/files/avatars/",exchange->{
      avatarChecks.incrementAndGet();
      int status=exchange.getRequestURI().getPath().contains("/owned-avatar/")?200:403;
      exchange.sendResponseHeaders(status,-1);exchange.close();
    });
    dependencies.start();String dependencyUrl="http://127.0.0.1:"+dependencies.getAddress().getPort();String id;
    try {
      try(var app=start(rootUrl+schema,dependencyUrl,dependencyUrl)) {
        var users=app.getBean(UserIdentityStore.class);id=users.resolve("+8613800000099").id();
        String other=users.resolve("+8613800000098").id();
        String url="http://127.0.0.1:"+app.getWebServer().getPort()+"/api/v1/users/me";
        assertEquals(401,send(url,"PATCH",null,"{\"nickname\":\"hello\"}").statusCode());
        var noCsrf=HttpRequest.newBuilder(URI.create(url)).header("Cookie",id).header("Content-Type","application/json").method("PATCH",HttpRequest.BodyPublishers.ofString("{\"nickname\":\"hello\"}")).build();
        assertEquals(403,HttpClient.newHttpClient().send(noCsrf,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400,send(url,"PATCH",id,"{}").statusCode());
        assertEquals(400,send(url,"PATCH",id,"{\"nickname\":\"  \"}").statusCode());
        assertEquals(400,send(url,"PATCH",id,"{\"nickname\":\""+"x".repeat(65)+"\"}").statusCode());
        assertEquals(403,send(url,"PATCH",id,"{\"avatarFileId\":\"someone-elses-file\"}").statusCode());
        var saved=send(url,"PATCH",id,"{\"nickname\":\" 新昵称 \",\"avatarFileId\":\"owned-avatar\",\"id\":\""+other+"\",\"enabled\":false}");
        assertEquals(200,saved.statusCode(),saved.body());
        assertEquals("新昵称",json(saved).path("nickname").asText());
        assertEquals("owned-avatar",json(send(url,"GET",id,null)).path("avatarFileId").asText());
        assertEquals("用户0098",users.find(other).nickname());assertTrue(users.find(id).enabled());
        assertEquals(200,send(url,"PATCH",id,"{\"nickname\":\"新昵称\"}").statusCode());
        assertEquals(2,avatarChecks.get());
        users.setEnabled(id,false);
        assertEquals(401,send(url,"PATCH",id,"{\"nickname\":\"blocked\"}").statusCode());users.setEnabled(id,true);
      }
      try(var app=start(rootUrl+schema,dependencyUrl,dependencyUrl)) {
        var user=app.getBean(UserIdentityStore.class).find(id);
        assertEquals("新昵称",user.nickname());assertEquals("owned-avatar",user.avatarFileId());
        String url="http://127.0.0.1:"+app.getWebServer().getPort()+"/api/v1/users/me";
        assertEquals(200,send(url,"PATCH",id,"{\"avatarFileId\":\"\"}").statusCode());
        assertNull(app.getBean(UserIdentityStore.class).find(id).avatarFileId());
      }
    } finally { dependencies.stop(0);db.execute("DROP DATABASE "+schema); }
  }
  private static String username() { return System.getenv().getOrDefault("IAM_TEST_MYSQL_USERNAME", "root"); }
  private static String password() { return System.getenv().getOrDefault("IAM_TEST_MYSQL_PASSWORD", ""); }
  private static com.fasterxml.jackson.databind.JsonNode json(HttpResponse<String> response) throws Exception { return new ObjectMapper().readTree(response.body()); }
  private static HttpResponse<String> send(String url, String method, String cookie, String body) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json");
    if (cookie != null) request.header("Cookie", cookie);
    if (body != null && !"admin-no-csrf".equals(cookie)) request.header("X-CSRF-TOKEN", "csrf");
    return HttpClient.newHttpClient().send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }
  private static ServletWebServerApplicationContext start(String url) { return start(url, "http://127.0.0.1:8210"); }
  private static ServletWebServerApplicationContext start(String url, String iamUrl) { return start(url, iamUrl, "http://127.0.0.1:8219"); }
  private static ServletWebServerApplicationContext start(String url, String iamUrl, String storageUrl) {
    return (ServletWebServerApplicationContext) new SpringApplicationBuilder(TemplateApplication.class).web(WebApplicationType.SERVLET)
      .run("--spring.config.location=classpath:/application.yml", "--spring.datasource.url=" + url,
          "--spring.datasource.username=" + username(), "--spring.datasource.password=" + password(), "--server.port=0",
          "--user.iam-service-url=" + iamUrl, "--user.storage-service-url="+storageUrl, "--server.address=127.0.0.1", "--logging.level.root=ERROR", "--spring.main.banner-mode=off");
  }
  private static HttpResponse<String> resolve(String url, String token) throws Exception { return resolve(url, token, "+8613800000001"); }
  private static HttpResponse<String> resolve(String url, String token, String phone) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json");
    if (token != null) request.header("X-IAM-INTERNAL-TOKEN", token);
    return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString("{\"phone\":\"" + phone + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
  }
}
